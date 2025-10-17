@echo off
setlocal enabledelayedexpansion

REM Create directories
if not exist keys mkdir keys
if not exist keys\private mkdir keys\private

REM List of clients
set clients=clientA clientB clientC clientD clientE clientF clientG clientH clientI clientJ
REM List of nodes
set nodes=n1 n2 n3 n4 n5 n6 n7

REM Create manifest.json
(echo {) > keys\manifest.json
set first=1

REM Generate keys for clients
for %%C in (%clients%) do (
    openssl genpkey -algorithm ED25519 -out keys\private\%%C.pem
    openssl pkey -in keys\private\%%C.pem -pubout -out keys\%%C.pub.pem
    set "pubkey="
    for /f "delims=" %%P in (keys\%%C.pub.pem) do (
        set "line=%%P"
        set "line=!line: =!"
        set "pubkey=!pubkey!!line!\\n!"
    )
    if !first! == 1 (
        set first=0
    ) else (
        echo ,>> keys\manifest.json
    )
    echo   "%%C": "!pubkey:~0,-2!" >> keys\manifest.json
    del keys\%%C.pub.pem
)

REM Generate keys for nodes
for %%N in (%nodes%) do (
    openssl genpkey -algorithm ED25519 -out keys\private\%%N.pem
    openssl pkey -in keys\private\%%N.pem -pubout -out keys\%%N.pub.pem
    set "pubkey="
    for /f "delims=" %%P in (keys\%%N.pub.pem) do (
        set "line=%%P"
        set "line=!line: =!"
        set "pubkey=!pubkey!!line!\\n!"
    )
    if !first! == 1 (
        set first=0
    ) else (
        echo ,>> keys\manifest.json
    )
    echo   "%%N": "!pubkey:~0,-2!" >> keys\manifest.json
    del keys\%%N.pub.pem
)

echo } >> keys\manifest.json

echo Key generation complete. Private keys in keys\private\, manifest.json with public keys in keys\
endlocal
