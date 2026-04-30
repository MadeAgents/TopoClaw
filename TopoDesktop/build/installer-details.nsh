; NSIS include file for electron-builder.
; Keep this file present because package.json references:
;   build.nsis.include = "build/installer-details.nsh"
; Add custom NSIS macros here when needed.
; Enable installer/uninstaller details area so users can inspect progress.
; Must be declared as global installer attributes (not inside customInit function).
ShowInstDetails show
ShowUninstDetails show
