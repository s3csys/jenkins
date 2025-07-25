def deployApp(envParams) {
    bat """
    @echo off
    REM ===  Enable delayed expansion for runtime variable updates ===
    setlocal enabledelayedexpansion

    REM ===  Set deployment variables using passed parameters ===
    set "DEPLOY_DIR=${envParams.DEPLOY_DIR}"
    set "SITE_NAME=${envParams.SITE_NAME}"
    set "RELEASES_DIR=!DEPLOY_DIR!\\releases"
    set "SHARED_DIR=!DEPLOY_DIR!\\shared"
    set "SOURCE_DIR=${envParams.SOURCE_DIR}"
    set "API_SERVER=${envParams.API_SERVER}"

    REM ===  Generate timestamp using PowerShell ===
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format 'dddd-MMMM-dd-yyyy-HH-mm-tt'"') do set "TIMESTAMP=%%i"
    set "NEW_RELEASE_DIR=!RELEASES_DIR!\\!TIMESTAMP!"

    REM ===  Create directories if they do not exist ===
    if not exist "!RELEASES_DIR!" mkdir "!RELEASES_DIR!"
    if not exist "!NEW_RELEASE_DIR!" mkdir "!NEW_RELEASE_DIR!"

    REM ===  Robocopy with log and error handling ===
    echo [INFO] Copying files from !SOURCE_DIR! to !NEW_RELEASE_DIR!
    robocopy "!SOURCE_DIR!" "!NEW_RELEASE_DIR!" /MIR /Z /XA:H /W:5 /NFL /NDL /NJH /NJS /nc /ns /np /LOG+:deploy.log
    if !errorlevel! GEQ 8 (
        echo [ERROR] Robocopy failed with error level !errorlevel!
        exit /b 1
    )

    REM ===  Remove previous 'current' symlink if it exists ===
    if exist "!DEPLOY_DIR!\\current" (
        echo [INFO] Removing previous symlink
        rmdir "!DEPLOY_DIR!\\current"
    )

    REM ===  Remove existing Web.config in new release dir if present ===
    if exist "!NEW_RELEASE_DIR!\\Web.config" (
        echo [INFO] Removing existing Web.config
        del "!NEW_RELEASE_DIR!\\Web.config"
    )

    REM ===  Create new symlinks ===
    echo [INFO] Creating new symlinks
    mklink /D "!DEPLOY_DIR!\\current" "!NEW_RELEASE_DIR!" || (
        echo [ERROR] Failed to create current symlink
        exit /b 1
    )
    copy /Y "!SHARED_DIR!\\Web.config" "!NEW_RELEASE_DIR!\\wwwroot\\Web.config" || (
        echo [ERROR] Failed to copy Web.config file from shared directory
        exit /b 1
    )

    REM ===  Update IIS site path and restart site using PowerShell ===
    echo [INFO] Updating IIS site configuration
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Import-Module WebAdministration; Set-ItemProperty 'IIS:\\Sites\\!SITE_NAME!' -Name physicalPath -Value '!DEPLOY_DIR!\\current'; Stop-WebSite -Name '!SITE_NAME!'; Start-WebSite -Name '!SITE_NAME!'"

    REM ===  Clean IIS temp files ===
    echo [INFO] Cleaning IIS temporary files
    %windir%\\system32\\inetsrv\\appcmd.exe stop site "!SITE_NAME!"

    REM ===  Delayed expansion enabled, TEMP_DIR resolves correctly now ===
    set "TEMP_DIR=C:\\inetpub\\temp\\IIS Temporary Compressed Files\\!SITE_NAME!"
    
    REM === Use a simplified approach with goto statements ===
    if exist "!TEMP_DIR!\\*" (
        goto :temp_dir_exists
    ) else (
        echo [INFO] Temp directory not found: !TEMP_DIR!
        goto :temp_dir_check_done
    )
    
    :temp_dir_exists
    REM === This code only runs if directory DOES exist ===
    echo [INFO] Deleting temp directory: !TEMP_DIR!
    rmdir /s /q "!TEMP_DIR!"
    if !errorlevel! neq 0 (
        echo [WARNING] Failed to delete temp directory (Error: !errorlevel!)
    )

    :temp_dir_check_done
    REM === Recycling App Pool ===
    echo [INFO] Recycling App Pool: !SITE_NAME!
    %windir%\\system32\\inetsrv\\appcmd.exe recycle apppool /apppool.name:"!SITE_NAME!"
    
    REM === Starting the site to ensure it's running ===
    echo [INFO] Starting Site: !SITE_NAME!
    %windir%\\system32\\inetsrv\\appcmd.exe start site "!SITE_NAME!"
    
    REM === Starting the API Server to ensure it's running ===
    echo [INFO] Starting Site API: !API_SERVER!
    powershell -Command "Restart-Service !API_SERVER!; Get-Service !API_SERVER!" || (
        echo [ERROR] Failed to start API server
        exit /b 1
    )

    REM ===  Clean old releases (keep last N) ===
    echo [INFO] Cleaning old releases (keeping last ${env.RELEASES_TO_KEEP})
    pushd "!RELEASES_DIR!"
    REM === Only keep the latest N directories using skip ===
    for /f "skip=${env.RELEASES_TO_KEEP} delims=" %%d in ('dir /b /ad /o-d') do (
        echo Deleting old build: %%d
        rmdir /s /q "%%d"
    )
    popd

    endlocal
    echo [SUCCESS] Deployment completed
    """
}
