def deployApp(envParams) {
    bat """
    @echo off
    set "DEPLOY_DIR=${envParams.DEPLOY_DIR}"
    set "SITE_NAME=${envParams.SITE_NAME}"
    set "RELEASES_DIR=%DEPLOY_DIR%\\releases"
    set "SHARED_DIR=%DEPLOY_DIR%\\shared"
    set "SOURCE_DIR=${envParams.SOURCE_DIR}"

    :: Timestamp generation using old format
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format 'dddd-MMMM-dd-yyyy-HH-mm-tt'"') do set "TIMESTAMP=%%i"
    set "NEW_RELEASE_DIR=%RELEASES_DIR%\\%TIMESTAMP%"

    :: Create directories
    if not exist "%RELEASES_DIR%" mkdir "%RELEASES_DIR%"
    if not exist "%NEW_RELEASE_DIR%" mkdir "%NEW_RELEASE_DIR%"

    :: File copy with detailed logging
    echo [INFO] Copying files from %SOURCE_DIR% to %NEW_RELEASE_DIR%
    robocopy "%SOURCE_DIR%" "%NEW_RELEASE_DIR%" /MIR /Z /XA:H /W:5 /NFL /NDL /NJH /NJS /nc /ns /np /LOG+:deploy.log
    if %errorlevel% GEQ 8 (
        echo [ERROR] Robocopy failed with error level %errorlevel%
        exit /b 1
    )

    :: Clean previous symlink
    if exist "%DEPLOY_DIR%\\current" (
        echo [INFO] Removing previous symlink
        rmdir "%DEPLOY_DIR%\\current"
    )

    :: Remove existing Web.config if present
    if exist "%NEW_RELEASE_DIR%\\Web.config" (
        echo [INFO] Removing existing Web.config
        del "%NEW_RELEASE_DIR%\\Web.config"
    )

    :: Create new symlinks
    echo [INFO] Creating new symlinks
    mklink /D "%DEPLOY_DIR%\\current" "%NEW_RELEASE_DIR%" || (
        echo [ERROR] Failed to create current symlink
        exit /b 1
    )
    mklink /H "%NEW_RELEASE_DIR%\\Web.config" "%SHARED_DIR%\\Web.config" || (
        echo [ERROR] Failed to create Web.config symlink
        exit /b 1
    )

    :: Update IIS site
    echo [INFO] Updating IIS site configuration
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Import-Module WebAdministration; Set-ItemProperty 'IIS:\\Sites\\%SITE_NAME%' -Name physicalPath -Value '%DEPLOY_DIR%\\current'; Stop-WebSite -Name '%SITE_NAME%'; Start-WebSite -Name '%SITE_NAME%'"

    :: Clean IIS temp files
    echo [INFO] Cleaning IIS temporary files
    %windir%\\system32\\inetsrv\\appcmd.exe stop site "%SITE_NAME%"
    set "TEMP_DIR=C:\\inetpub\\temp\\IIS Temporary Compressed Files\\%SITE_NAME%"
    if exist "!TEMP_DIR!" (
        echo [INFO] Deleting temp directory: !TEMP_DIR!
        rmdir /s /q "!TEMP_DIR!"
        if %errorlevel% neq 0 (
            echo [WARNING] Failed to delete temp directory (Error: %errorlevel%)
        )
    ) else (
        echo [INFO] Temp directory not found: !TEMP_DIR!
    )
    %windir%\\system32\\inetsrv\\appcmd.exe start site "%SITE_NAME%"

    :: Clean old releases (keep last N as defined in environment)
    echo [INFO] Cleaning old releases (keeping last ${env.RELEASES_TO_KEEP})
    pushd "%RELEASES_DIR%"
    set COUNT=0
    for /f "skip=3 delims=" %%d in ('dir /b /ad /o-d') do (
        echo Deleting old build: %%d
        rmdir /s /q "%%d"
    )
    popd
    endlocal
    echo [SUCCESS] Deployment completed
    """
}