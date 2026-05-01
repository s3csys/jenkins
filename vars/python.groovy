def deployApp(envParams) {
    bat """
    @echo off
    REM === Enable Long Path Support in Windows Registry ===
    REM === reg add "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\FileSystem" /v LongPathsEnabled /t REG_DWORD /d 1 /f >nul 2>&1 ===

    REM === Enable delayed expansion for runtime variable updates ===
    setlocal enabledelayedexpansion

    REM === Set deployment variables using passed parameters ===
    set "DEPLOY_DIR=${envParams.DEPLOY_DIR}"
    set "RELEASES_DIR=!DEPLOY_DIR!\\releases"
    set "SHARED_DIR=!DEPLOY_DIR!\\shared"
    set "SOURCE_DIR=${envParams.SOURCE_DIR}"
    set "VENV_NAME=${envParams.VENV_NAME}"
    set "SERVICE_NAME=${envParams.SERVICE_NAME}"

    REM === Generate timestamp using PowerShell ===
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format 'dddd-MMMM-dd-yyyy-HH-mm-tt'"') do set "TIMESTAMP=%%i"
    set "NEW_RELEASE_DIR=!RELEASES_DIR!\\!TIMESTAMP!"
    set "VENV_PATH=!NEW_RELEASE_DIR!\\!VENV_NAME!"

    REM === Create directories if they do not exist ===
    if not exist "!RELEASES_DIR!" (
        mkdir "!RELEASES_DIR!" || (
            echo [ERROR] Failed to create RELEASES_DIR: !RELEASES_DIR!
            exit /b 1
        )
    )
    if not exist "!NEW_RELEASE_DIR!" (
        mkdir "!NEW_RELEASE_DIR!" || (
            echo [ERROR] Failed to create NEW_RELEASE_DIR: !NEW_RELEASE_DIR!
            exit /b 1
        )
    )

    REM === Robocopy source to new release directory ===
    echo [INFO] Copying files from !SOURCE_DIR! to !NEW_RELEASE_DIR!
    robocopy "!SOURCE_DIR!" "!NEW_RELEASE_DIR!" /MIR /Z /XA:H /W:5 /NFL /NDL /NJH /NJS /nc /ns /np /LOG+:deploy.log /XD venv .vscode __pycache__
    if !errorlevel! GEQ 8 (
        echo [ERROR] Robocopy failed with error level !errorlevel!
        exit /b 1
    )

    REM === Copy requirements.txt from shared directory if it exists ===
    if exist "!SHARED_DIR!\\requirements.txt" (
        echo [INFO] Copying requirements.txt from !SHARED_DIR! to !NEW_RELEASE_DIR!
        copy /Y "!SHARED_DIR!\\requirements.txt" "!NEW_RELEASE_DIR!\\requirements.txt" || (
            echo [ERROR] Failed to copy requirements.txt from shared directory
            exit /b 1
        )
        copy /Y "!SHARED_DIR!\\env" "!NEW_RELEASE_DIR!\\.env" || (
            echo [ERROR] Failed to copy env from shared directory
            exit /b 1
        )
    )

    REM === Create virtual environment if it doesn't exist ===
    if not exist "!VENV_PATH!" (
        echo [INFO] Creating virtual environment at !VENV_PATH!
        python -m venv "!VENV_PATH!"
        if !errorlevel! neq 0 (
            echo [ERROR] Failed to create virtual environment
            exit /b 1
        )
    )

    REM === Install dependencies in virtual environment without activation ===
    echo [INFO] Updating dependencies in venv at !VENV_PATH!
    if exist "!NEW_RELEASE_DIR!\\requirements.txt" (
        "!VENV_PATH!\\Scripts\\python.exe" -m pip install --upgrade pip
        if !errorlevel! neq 0 (
            echo [ERROR] Failed to upgrade pip
            exit /b 1
        )
        "!VENV_PATH!\\Scripts\\python.exe" -m pip install -r "!NEW_RELEASE_DIR!\\requirements.txt"
        if !errorlevel! neq 0 (
            echo [ERROR] Failed to install dependencies from requirements.txt
            exit /b 1
        )
    ) else (
        echo [WARNING] No requirements.txt found in source. Skipping pip install.
    )

    REM === Update 'current' symlink ===
    if exist "!DEPLOY_DIR!\\current" (
        echo [INFO] Removing previous symlink
        rmdir "!DEPLOY_DIR!\\current" || (
            echo [ERROR] Failed to remove old 'current' symlink
            exit /b 1
        )
    )
    echo [INFO] Creating new symlink to !NEW_RELEASE_DIR!
    mklink /D "!DEPLOY_DIR!\\current" "!NEW_RELEASE_DIR!" || (
        echo [ERROR] Failed to create new 'current' symlink
        exit /b 1
    )

    REM === Restart Python Service ===
    echo [INFO] Restarting service: !SERVICE_NAME!
    powershell -Command "Restart-Service !SERVICE_NAME!; Get-Service !SERVICE_NAME!" || (
        echo [ERROR] Failed to restart service !SERVICE_NAME!
        exit /b 1
    )

    REM === Clean old releases (keep last N) ===
    echo [INFO] Cleaning old releases (keeping last ${env.RELEASES_TO_KEEP})
    pushd "!RELEASES_DIR!"
    for /f "skip=${env.RELEASES_TO_KEEP} delims=" %%d in ('dir /b /ad /o-d') do (
        echo [INFO] Deleting old build: %%d
        rmdir /s /q "%%d"
    )
    popd

    endlocal
    echo [SUCCESS] Python deployment completed successfully
    """
}
