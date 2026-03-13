/**
 * Liberty Show — Core Application Engine
 * TV-First | D-Pad Navigation | Torrent Manager | Storage Picker
 * ================================================================
 */

'use strict';

// ══════════════════════════════════════════
// APP STATE
// ══════════════════════════════════════════
const AppState = {
    currentPage: 'home',
    downloads: [],
    storageSelection: null,          // 'internal' | 'usb'
    pendingMagnet: null,
    usbAvailable: false,
    usbFreeGB: 0,
    internalFreeGB: 0,
    playerOpen: false,
    currentDownload: null,
    focusIndex: 0,
    controlsTimer: null,
};

// ── Simulated "bridge" to Android (WebView calls Java/Kotlin) ──
const AndroidBridge = {
    isAndroidApp: () => typeof Android !== 'undefined',

    getUsbStatus: () => {
        if (AndroidBridge.isAndroidApp()) return Android.getUsbStatus();
        // Dev mode: simulated
        return JSON.stringify({ available: true, freeGB: 237.4, label: 'USB_64G' });
    },

    getInternalFree: () => {
        if (AndroidBridge.isAndroidApp()) return Android.getInternalFree();
        return JSON.stringify({ freeGB: 12.9 });
    },

    requestSafPermission: (path) => {
        if (AndroidBridge.isAndroidApp()) return Android.requestSafPermission(path);
        console.log('[BRIDGE] SAF permission requested for:', path);
        return 'granted';
    },

    startTorrentEngine: (magnet, savePath) => {
        if (AndroidBridge.isAndroidApp()) return Android.startTorrentEngine(magnet, savePath);
        console.log('[BRIDGE] Torrent engine start:', magnet, '->', savePath);
        return JSON.stringify({ id: `tor_${Date.now()}`, name: extractMagnetName(magnet) });
    },

    pauseTorrent: (id) => {
        if (AndroidBridge.isAndroidApp()) return Android.pauseTorrent(id);
        console.log('[BRIDGE] Pause:', id);
    },

    resumeTorrent: (id) => {
        if (AndroidBridge.isAndroidApp()) return Android.resumeTorrent(id);
        console.log('[BRIDGE] Resume:', id);
    },

    deleteTorrent: (id) => {
        if (AndroidBridge.isAndroidApp()) return Android.deleteTorrent(id);
        console.log('[BRIDGE] Delete:', id);
    },

    openVideoPlayer: (filePath) => {
        if (AndroidBridge.isAndroidApp()) return Android.openNativePlayer(filePath);
        console.log('[BRIDGE] Open native player:', filePath);
        return false; // fallback to built-in
    },

    clearCache: () => {
        if (AndroidBridge.isAndroidApp()) return Android.clearCache();
        console.log('[BRIDGE] Cache cleared');
    },
};

// ══════════════════════════════════════════
// UTILITY FUNCTIONS
// ══════════════════════════════════════════
function extractMagnetName(magnet) {
    const match = magnet.match(/dn=([^&]+)/);
    if (match) return decodeURIComponent(match[1].replace(/\+/g, ' '));
    const hashMatch = magnet.match(/btih:([a-fA-F0-9]+)/i);
    if (hashMatch) return `Torrent_${hashMatch[1].substring(0, 8).toUpperCase()}`;
    return `Torrent_${Date.now()}`;
}

function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function formatSpeed(bps) {
    return formatBytes(bps) + '/s';
}

function sanitizeHTML(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function generateId() {
    return `tor_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

// ══════════════════════════════════════════
// STORAGE DETECTION
// ══════════════════════════════════════════
function detectStorage() {
    try {
        const usbData = JSON.parse(AndroidBridge.getUsbStatus());
        AppState.usbAvailable = usbData.available;
        AppState.usbFreeGB = usbData.freeGB || 0;
        AppState.usbLabel = usbData.label || 'USB Drive';
    } catch (e) {
        AppState.usbAvailable = false;
    }

    try {
        const intData = JSON.parse(AndroidBridge.getInternalFree());
        AppState.internalFreeGB = intData.freeGB || 0;
    } catch (e) {
        AppState.internalFreeGB = 0;
    }

    updateStatsBar();
}

// ══════════════════════════════════════════
// TOAST NOTIFICATIONS
// ══════════════════════════════════════════
function showToast(message, type = 'info', duration = 3500) {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    const icons = { success: '✅', error: '❌', info: 'ℹ️', warning: '⚠️' };
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `<span>${icons[type] || 'ℹ️'}</span><span>${sanitizeHTML(message)}</span>`;
    container.appendChild(toast);
    setTimeout(() => {
        if (container.contains(toast)) container.removeChild(toast);
    }, duration + 400);
}

// ══════════════════════════════════════════
// NAVIGATION (D-PAD ENGINE)
// ══════════════════════════════════════════
const DPad = {
    focusableSelector: '[data-focusable="true"]',
    currentElement: null,

    init() {
        document.addEventListener('keydown', (e) => this.handleKey(e));
        // Set initial focus
        this.focusFirst();
    },

    focusFirst() {
        const first = document.querySelector(this.focusableSelector);
        if (first) this.setFocus(first);
    },

    setFocus(el) {
        if (!el) return;
        if (this.currentElement) {
            this.currentElement.classList.remove('focused');
            this.currentElement.blur();
        }
        this.currentElement = el;
        el.classList.add('focused');
        el.focus({ preventScroll: false });
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    },

    moveFocus(direction) {
        const all = [...document.querySelectorAll(this.focusableSelector)]
            .filter(el => {
                const style = window.getComputedStyle(el);
                return style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null;
            });

        if (!all.length) return;

        const current = this.currentElement;
        if (!current || !all.includes(current)) {
            this.setFocus(all[0]);
            return;
        }

        const rect = current.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;

        let best = null, bestScore = Infinity;

        all.forEach(el => {
            if (el === current) return;
            const r = el.getBoundingClientRect();
            const ex = r.left + r.width / 2;
            const ey = r.top + r.height / 2;
            const dx = ex - cx, dy = ey - cy;

            let inDir = false;
            switch (direction) {
                case 'up': inDir = dy < -8 && Math.abs(dx) < Math.abs(dy) * 2.2; break;
                case 'down': inDir = dy > 8 && Math.abs(dx) < Math.abs(dy) * 2.2; break;
                case 'left': inDir = dx < -8 && Math.abs(dy) < Math.abs(dx) * 2.2; break;
                case 'right': inDir = dx > 8 && Math.abs(dy) < Math.abs(dx) * 2.2; break;
            }

            if (inDir) {
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < bestScore) { bestScore = dist; best = el; }
            }
        });

        if (best) this.setFocus(best);
    },

    handleKey(e) {
        const modal = document.getElementById('storage-modal');
        const player = document.getElementById('player-overlay');

        switch (e.key) {
            case 'ArrowUp':
                e.preventDefault();
                this.moveFocus('up');
                break;
            case 'ArrowDown':
                e.preventDefault();
                this.moveFocus('down');
                break;
            case 'ArrowLeft':
                e.preventDefault();
                this.moveFocus('left');
                break;
            case 'ArrowRight':
                e.preventDefault();
                this.moveFocus('right');
                break;
            case 'Enter':
            case ' ':
                e.preventDefault();
                if (this.currentElement) this.currentElement.click();
                break;
            case 'Backspace':
            case 'Escape':
                e.preventDefault();
                if (player.classList.contains('active')) {
                    closePlayer();
                } else if (modal.classList.contains('active')) {
                    closeStorageModal();
                }
                break;
            case 'MediaPlayPause':
                e.preventDefault();
                togglePlayPause();
                break;
        }

        // Show player controls on any keypress during playback
        if (AppState.playerOpen) {
            showPlayerControls();
        }
    },
};

// ══════════════════════════════════════════
// PAGE NAVIGATION
// ══════════════════════════════════════════
function navigateTo(page) {
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));

    const pageEl = document.getElementById(`page-${page}`);
    const tabEl = document.getElementById(`tab-${page}`);
    if (pageEl) pageEl.classList.add('active');
    if (tabEl) tabEl.classList.add('active');

    AppState.currentPage = page;

    // Page-specific init
    if (page === 'downloads') renderDownloads();
    if (page === 'settings') renderSettings();

    // Re-focus first element
    setTimeout(() => DPad.focusFirst(), 50);
}

// ══════════════════════════════════════════
// STORAGE SELECTION MODAL
// ══════════════════════════════════════════
function openStorageModal(magnetOrFile) {
    AppState.pendingMagnet = magnetOrFile;
    detectStorage();

    // Update modal content
    const infoEl = document.getElementById('modal-torrent-info');
    infoEl.textContent = magnetOrFile.length > 90
        ? magnetOrFile.substring(0, 90) + '...'
        : magnetOrFile;

    // USB option availability
    const usbOption = document.getElementById('storage-opt-usb');
    const usbAvail = document.getElementById('usb-avail');
    const intAvail = document.getElementById('int-avail');

    if (AppState.usbAvailable) {
        usbOption.classList.remove('usb-missing');
        usbAvail.textContent = `${AppState.usbFreeGB.toFixed(1)} GB free`;
    } else {
        usbOption.classList.add('usb-missing');
        usbAvail.textContent = 'Not detected';
    }

    intAvail.textContent = `${AppState.internalFreeGB.toFixed(1)} GB free`;

    // Default: internal
    selectStorage('internal');

    // Show modal
    const modal = document.getElementById('storage-modal');
    modal.classList.add('active');

    // Focus first storage option
    setTimeout(() => {
        const internalOpt = document.getElementById('storage-opt-internal');
        DPad.setFocus(internalOpt);
    }, 100);
}

function closeStorageModal() {
    document.getElementById('storage-modal').classList.remove('active');
    AppState.pendingMagnet = null;
    AppState.storageSelection = null;
    // Re-focus add button
    setTimeout(() => {
        const addBtn = document.getElementById('btn-add-magnet');
        if (addBtn) DPad.setFocus(addBtn);
    }, 100);
}

function selectStorage(type) {
    AppState.storageSelection = type;
    document.querySelectorAll('.storage-option').forEach(o => o.classList.remove('selected'));
    const selected = document.getElementById(`storage-opt-${type}`);
    if (selected) {
        selected.classList.add('selected');
        DPad.setFocus(selected);
    }
}

function confirmStorageAndStart() {
    if (!AppState.storageSelection) {
        showToast('Please select a storage location first', 'warning');
        return;
    }
    if (!AppState.pendingMagnet) {
        showToast('No torrent pending', 'error');
        return;
    }

    // USB SAF check
    if (AppState.storageSelection === 'usb') {
        const safResult = AndroidBridge.requestSafPermission('/usb_storage');
        if (safResult !== 'granted') {
            showToast('USB permission denied. Falling back to Internal.', 'warning');
            AppState.storageSelection = 'internal';
        }
    }

    const savePath = AppState.storageSelection === 'usb'
        ? '/usb_storage/LibertyShow/'
        : '/sdcard/LibertyShow/';

    // Start the engine
    let result;
    try {
        result = JSON.parse(AndroidBridge.startTorrentEngine(AppState.pendingMagnet, savePath));
    } catch (e) {
        // Dev mode fallback
        result = {
            id: generateId(),
            name: extractMagnetName(AppState.pendingMagnet),
        };
    }

    // Add to state
    const newDownload = {
        id: result.id,
        name: result.name || 'Unknown Torrent',
        magnet: AppState.pendingMagnet,
        savePath,
        storage: AppState.storageSelection,
        progress: 0,
        speed: 0,
        seeds: 0,
        peers: 0,
        size: 0,
        status: 'downloading',
        addedAt: Date.now(),
        thumb: null,
    };

    AppState.downloads.push(newDownload);
    saveDownloads();

    closeStorageModal();

    // Navigate to downloads to show progress
    navigateTo('downloads');
    showToast(`✅ "${newDownload.name}" started on ${AppState.storageSelection === 'usb' ? 'USB' : 'Internal'}`, 'success');

    // Start simulated progress (dev mode only)
    if (!AndroidBridge.isAndroidApp()) {
        simulateDownloadProgress(newDownload.id);
    }
}

// ══════════════════════════════════════════
// TORRENT INPUT HANDLER
// ══════════════════════════════════════════
function handleAddMagnet() {
    const input = document.getElementById('magnet-input');
    const topInput = document.getElementById('search-input');
    let value = (input ? input.value.trim() : '') || (topInput ? topInput.value.trim() : '');

    if (!value) {
        showToast('Please paste a magnet link or .torrent URL', 'warning');
        if (input) DPad.setFocus(input);
        return;
    }

    // Basic validation
    const isMagnet = value.startsWith('magnet:?');
    const isTorrent = value.startsWith('http') && value.endsWith('.torrent');
    const isHash = /^[a-fA-F0-9]{40}$/.test(value);

    if (!isMagnet && !isTorrent && !isHash) {
        showToast('Invalid magnet link or torrent URL', 'error');
        return;
    }

    // Normalize hash -> magnet
    if (isHash) value = `magnet:?xt=urn:btih:${value}`;

    openStorageModal(value);

    if (input) input.value = '';
    if (topInput) topInput.value = '';
}

// ══════════════════════════════════════════
// DOWNLOADS RENDERING
// ══════════════════════════════════════════
function renderDownloads() {
    const container = document.getElementById('downloads-list');
    if (!container) return;

    if (!AppState.downloads.length) {
        container.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-icon">⬇️</div>
        <div class="empty-state-title">No Downloads Yet</div>
        <div class="empty-state-text">Add a magnet link or torrent URL from the search bar to get started. Downloads will appear here.</div>
      </div>`;
        return;
    }

    container.innerHTML = AppState.downloads.map(dl => buildDownloadCard(dl)).join('');

    // Re-attach event listeners
    container.querySelectorAll('.download-card').forEach(card => {
        const id = card.dataset.id;
        card.addEventListener('click', () => handleCardClick(id));
        card.querySelector('.btn-play')?.addEventListener('click', (e) => { e.stopPropagation(); openPlayer(id); });
        card.querySelector('.btn-pause')?.addEventListener('click', (e) => { e.stopPropagation(); toggleDownload(id); });
        card.querySelector('.btn-delete')?.addEventListener('click', (e) => { e.stopPropagation(); deleteDownload(id); });
    });
}

function buildDownloadCard(dl) {
    const pct = Math.min(100, Math.floor(dl.progress));
    const isPlaying = pct > 5;
    const storageClass = dl.storage === 'usb' ? 'storage-usb' : 'storage-internal';
    const storageIcon = dl.storage === 'usb' ? '💾' : '📱';
    const statusClass = `status-${dl.status}`;
    const statusLabel = dl.status.charAt(0).toUpperCase() + dl.status.slice(1);

    return `
    <div class="download-card focusable" data-focusable="true" tabindex="0" data-id="${sanitizeHTML(dl.id)}">
      <div class="download-thumb">
        ${dl.thumb ? `<img src="${dl.thumb}" alt="thumb">` : '🎬'}
      </div>
      <div class="download-info">
        <div class="download-title">${sanitizeHTML(dl.name)}</div>
        <div class="download-meta">
          <span class="meta-badge ${storageClass}">${storageIcon} ${dl.storage === 'usb' ? 'USB' : 'Internal'}</span>
          <span class="meta-badge ${statusClass}">● ${statusLabel}</span>
          ${dl.speed > 0 ? `<span class="meta-badge">⬇ ${formatSpeed(dl.speed)}</span>` : ''}
          ${dl.seeds > 0 ? `<span class="meta-badge">🌱 ${dl.seeds} seeds</span>` : ''}
          <span class="meta-badge">${pct}%</span>
        </div>
        <div class="progress-bar-wrap">
          <div class="progress-bar-fill" style="width:${pct}%"></div>
        </div>
      </div>
      <div class="download-actions">
        ${isPlaying ? `<button class="icon-btn play-btn btn-play focusable" data-focusable="true" tabindex="0" title="Play">▶</button>` : ''}
        <button class="icon-btn btn-pause focusable" data-focusable="true" tabindex="0"
          title="${dl.status === 'paused' ? 'Resume' : 'Pause'}">
          ${dl.status === 'paused' ? '▶' : '⏸'}
        </button>
        <button class="icon-btn btn-delete focusable" data-focusable="true" tabindex="0" title="Delete">🗑</button>
      </div>
    </div>`;
}

function handleCardClick(id) {
    const dl = AppState.downloads.find(d => d.id === id);
    if (!dl) return;
    if (dl.progress > 5) openPlayer(id);
}

function toggleDownload(id) {
    const dl = AppState.downloads.find(d => d.id === id);
    if (!dl) return;

    if (dl.status === 'paused') {
        AndroidBridge.resumeTorrent(id);
        dl.status = 'downloading';
        showToast(`Resumed: ${dl.name}`, 'info');
    } else {
        AndroidBridge.pauseTorrent(id);
        dl.status = 'paused';
        showToast(`Paused: ${dl.name}`, 'info');
    }

    saveDownloads();
    renderDownloads();
}

function deleteDownload(id) {
    AppState.downloads = AppState.downloads.filter(d => d.id !== id);
    AndroidBridge.deleteTorrent(id);
    saveDownloads();
    renderDownloads();
    showToast('Download removed', 'info');
}

// ══════════════════════════════════════════
// VIDEO PLAYER
// ══════════════════════════════════════════
function openPlayer(torrentId) {
    const dl = AppState.downloads.find(d => d.id === torrentId);
    if (!dl) return;

    AppState.playerOpen = true;
    AppState.currentDownload = dl;

    // Attempt native player first (ExoPlayer via bridge)
    const nativeOpened = AndroidBridge.openVideoPlayer(dl.savePath + dl.name);
    if (!nativeOpened) {
        // Fallback: built-in HTML5 player
        const overlay = document.getElementById('player-overlay');
        const titleEl = document.getElementById('player-title');
        const video = document.getElementById('video-player');

        titleEl.textContent = dl.name;
        video.src = ''; // would be dl.savePath in production

        overlay.classList.add('active');
        video.play().catch(() => {
            showToast('Playback requires a downloaded file', 'warning');
        });

        showPlayerControls();
    }
}

function closePlayer() {
    const overlay = document.getElementById('player-overlay');
    const video = document.getElementById('video-player');
    overlay.classList.remove('active');
    video.pause();
    video.src = '';
    AppState.playerOpen = false;
    AppState.currentDownload = null;
}

function togglePlayPause() {
    const video = document.getElementById('video-player');
    if (!AppState.playerOpen || !video.src) return;
    if (video.paused) video.play();
    else video.pause();
}

function showPlayerControls() {
    const controls = document.querySelector('.player-controls');
    if (!controls) return;
    controls.classList.add('visible');
    clearTimeout(AppState.controlsTimer);
    AppState.controlsTimer = setTimeout(() => {
        controls.classList.remove('visible');
    }, 4000);
}

// ══════════════════════════════════════════
// SETTINGS RENDER
// ══════════════════════════════════════════
function renderSettings() {
    const prefs = loadPrefs();
    const hwAccel = document.getElementById('toggle-hw');
    const seqDl = document.getElementById('toggle-seq');
    const cacheClean = document.getElementById('toggle-cache');
    const lowRes = document.getElementById('toggle-lowres');
    if (hwAccel) hwAccel.checked = prefs.hwAcceleration;
    if (seqDl) seqDl.checked = prefs.sequentialDownload;
    if (cacheClean) cacheClean.checked = prefs.autoCacheClean;
    if (lowRes) lowRes.checked = prefs.lowResPosters;
}

function savePrefs() {
    const prefs = {
        hwAcceleration: document.getElementById('toggle-hw')?.checked ?? true,
        sequentialDownload: document.getElementById('toggle-seq')?.checked ?? true,
        autoCacheClean: document.getElementById('toggle-cache')?.checked ?? true,
        lowResPosters: document.getElementById('toggle-lowres')?.checked ?? true,
    };
    localStorage.setItem('liberty_prefs', JSON.stringify(prefs));
    showToast('Settings saved', 'success');
}

function loadPrefs() {
    try { return JSON.parse(localStorage.getItem('liberty_prefs')) || {}; }
    catch { return {}; }
}

// ══════════════════════════════════════════
// PERSISTENCE
// ══════════════════════════════════════════
function saveDownloads() {
    try {
        localStorage.setItem('liberty_downloads', JSON.stringify(AppState.downloads));
    } catch (e) {
        console.warn('[Liberty] Could not save downloads:', e);
    }
}

function loadDownloads() {
    try {
        const data = JSON.parse(localStorage.getItem('liberty_downloads'));
        if (Array.isArray(data)) AppState.downloads = data;
    } catch { AppState.downloads = []; }
}

// ══════════════════════════════════════════
// STATS BAR UPDATE
// ══════════════════════════════════════════
function updateStatsBar() {
    const activeEl = document.getElementById('stat-active');
    const usbEl = document.getElementById('stat-usb');
    const internalEl = document.getElementById('stat-internal');

    const active = AppState.downloads.filter(d => d.status === 'downloading').length;
    if (activeEl) activeEl.textContent = active;
    if (usbEl) usbEl.textContent = AppState.usbAvailable ? `${AppState.usbFreeGB.toFixed(0)}GB` : 'N/A';
    if (internalEl) internalEl.textContent = `${AppState.internalFreeGB.toFixed(0)}GB`;
}

// ══════════════════════════════════════════
// DEV: SIMULATE DOWNLOAD PROGRESS
// ══════════════════════════════════════════
function simulateDownloadProgress(id) {
    const interval = setInterval(() => {
        const dl = AppState.downloads.find(d => d.id === id);
        if (!dl || dl.status === 'paused') return;

        if (dl.progress >= 100) {
            dl.status = 'complete';
            dl.speed = 0;
            clearInterval(interval);
            showToast(`✅ "${dl.name}" completed!`, 'success');
        } else {
            dl.progress += Math.random() * 3.5 + 0.5;
            dl.speed = Math.floor(Math.random() * 3_000_000 + 500_000);
            dl.seeds = Math.floor(Math.random() * 80 + 10);
        }

        saveDownloads();
        if (AppState.currentPage === 'downloads') renderDownloads();
        updateStatsBar();
    }, 1200);
}

// ══════════════════════════════════════════
// MEMORY MANAGEMENT (Low-RAM)
// ══════════════════════════════════════════
function performCleanup() {
    // Clear image caches, revoke object URLs, etc.
    document.querySelectorAll('img[src^="blob:"]').forEach(img => {
        URL.revokeObjectURL(img.src);
        img.src = '';
    });
    AndroidBridge.clearCache();
}

// Register cleanup on visibility change (app goes to background)
document.addEventListener('visibilitychange', () => {
    if (document.hidden && loadPrefs().autoCacheClean) {
        performCleanup();
    }
});

// Cleanup on exit
window.addEventListener('beforeunload', () => {
    if (loadPrefs().autoCacheClean) performCleanup();
});

// ══════════════════════════════════════════
// INITIALIZATION
// ══════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
    loadDownloads();
    detectStorage();
    DPad.init();
    navigateTo('home');

    // Attach global UI event listeners
    document.getElementById('btn-add-magnet')?.addEventListener('click', handleAddMagnet);
    document.getElementById('btn-add-from-search')?.addEventListener('click', handleAddMagnet);
    document.getElementById('magnet-input')?.addEventListener('keydown', e => { if (e.key === 'Enter') handleAddMagnet(); });
    document.getElementById('search-input')?.addEventListener('keydown', e => { if (e.key === 'Enter') handleAddMagnet(); });

    document.getElementById('tab-home')?.addEventListener('click', () => navigateTo('home'));
    document.getElementById('tab-downloads')?.addEventListener('click', () => navigateTo('downloads'));
    document.getElementById('tab-settings')?.addEventListener('click', () => navigateTo('settings'));

    document.getElementById('btn-confirm-storage')?.addEventListener('click', confirmStorageAndStart);
    document.getElementById('btn-close-modal')?.addEventListener('click', closeStorageModal);
    document.getElementById('btn-cancel-modal')?.addEventListener('click', closeStorageModal);

    document.getElementById('storage-opt-internal')?.addEventListener('click', () => selectStorage('internal'));
    document.getElementById('storage-opt-usb')?.addEventListener('click', () => selectStorage('usb'));

    document.getElementById('btn-player-close')?.addEventListener('click', closePlayer);
    document.getElementById('btn-player-playpause')?.addEventListener('click', togglePlayPause);

    document.getElementById('btn-save-settings')?.addEventListener('click', savePrefs);

    // Video seek/time display
    const video = document.getElementById('video-player');
    const seek = document.getElementById('player-seek');
    if (video && seek) {
        video.addEventListener('timeupdate', () => {
            if (video.duration) seek.value = (video.currentTime / video.duration) * 100;
        });
        seek.addEventListener('input', () => {
            if (video.duration) video.currentTime = (seek.value / 100) * video.duration;
        });
        video.addEventListener('mousemove', showPlayerControls);
    }

    // Quick-add button from hero
    document.getElementById('btn-hero-add')?.addEventListener('click', () => {
        const input = document.getElementById('magnet-input');
        if (input) { input.focus(); DPad.setFocus(input); }
    });

    document.getElementById('btn-hero-downloads')?.addEventListener('click', () => navigateTo('downloads'));

    showToast('Liberty Show ready 🚀', 'success', 2500);
});
