def deployApp(envParams) {
    bat """
    @echo off
    REM === Enable delayed expansion for runtime variable updates ===
    setlocal enabledelayedexpansion

    REM === Set deployment variables using passed parameters ===
    set "DEPLOY_DIR=${envParams.DEPLOY_DIR}"
    set "RELEASES_DIR=!DEPLOY_DIR!\\releases"
    set "SHARED_DIR=!DEPLOY_DIR!\\shared"
    set "SOURCE_DIR=${envParams.SOURCE_DIR}"
    set "VENV_PATH=${envParams.VENV_PATH}"
    set "SERVICE_NAME=${envParams.SERVICE_NAME}"

    REM === Generate timestamp using PowerShell ===
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format 'dddd-MMMM-dd-yyyy-HH-mm-tt'"') do set "TIMESTAMP=%%i"
    set "NEW_RELEASE_DIR=!RELEASES_DIR!\\!TIMESTAMP!"

    REM === Create directories if they do not exist ===
    if not exist "!RELEASES_DIR!" mkdir "!RELEASES_DIR!"
    if not exist "!NEW_RELEASE_DIR!" mkdir "!NEW_RELEASE_DIR!"

    REM === Robocopy source to new release directory ===
    echo [INFO] Copying files from !SOURCE_DIR! to !NEW_RELEASE_DIR!
    robocopy "!SOURCE_DIR!" "!NEW_RELEASE_DIR!" /MIR /Z /XA:H /W:5 /NFL /NDL /NJH /NJS /nc /ns /np /LOG+:deploy.log /XD venv .vscode __pycache__
    if !errorlevel! GEQ 8 (
        echo [ERROR] Robocopy failed with error level !errorlevel!
        exit /b 1
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
        "!VENV_PATH!\\Scripts\\python.exe" -m pip install -r "!NEW_RELEASE_DIR!\\requirements.txt"
        if !errorlevel! neq 0 (
            echo [ERROR] Failed to install dependencies
            exit /b 1
        )
    ) else (
        echo [WARNING] No requirements.txt found in source. Skipping pip install.
    )

    REM === Update 'current' symlink ===
    if exist "!DEPLOY_DIR!\\current" (
        echo [INFO] Removing previous symlink
        rmdir "!DEPLOY_DIR!\\current"
    )
    echo [INFO] Creating new symlink to !NEW_RELEASE_DIR!
    mklink /D "!DEPLOY_DIR!\\current" "!NEW_RELEASE_DIR!" || (
        echo [ERROR] Failed to create current symlink
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
