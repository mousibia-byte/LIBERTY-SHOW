@echo off
:: ============================================================
:: Liberty Show — Auto Git Push Script
:: Run this to push your project to GitHub and trigger APK build
:: ============================================================

echo.
echo ==========================================
echo   Liberty Show — GitHub Push Script
echo ==========================================
echo.

:: Check if git is installed
git --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Git is not installed! Download from: https://git-scm.com
    pause
    exit /b 1
)

:: Ask for GitHub repo URL
set /p REPO_URL="Paste your GitHub repo URL (e.g. https://github.com/username/LibertyShow.git): "
if "%REPO_URL%"=="" (
    echo [ERROR] No URL provided.
    pause
    exit /b 1
)

echo.
echo [1/5] Initializing Git repository...
git init
git branch -M main

echo.
echo [2/5] Adding all files...
git add .

echo.
echo [3/5] Creating first commit...
git commit -m "feat: Initial Liberty Show project — TV torrent player with WebView UI"

echo.
echo [4/5] Setting remote origin...
git remote remove origin 2>nul
git remote add origin %REPO_URL%

echo.
echo [5/5] Pushing to GitHub...
git push -u origin main

echo.
echo ==========================================
echo  SUCCESS! Check GitHub Actions for APK:
echo  %REPO_URL% → Actions tab
echo ==========================================
echo.
pause
