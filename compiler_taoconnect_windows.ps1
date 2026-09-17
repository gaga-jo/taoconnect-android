[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

function Refresh-ProcessPath {
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machinePath;$userPath"
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell transforme parfois la sortie d'erreur d'un programme
        # externe en erreur bloquante. Ici, le code de sortie reste la reference.
        $ErrorActionPreference = "SilentlyContinue"
        $output = ((& $FilePath @Arguments 2>$null) | Out-String).Trim()
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Invoke-NativeInteractive {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $FilePath @Arguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    return $exitCode
}

function Ensure-Command {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WingetId,
        [Parameter(Mandatory = $true)][string]$DisplayName
    )

    if (Get-Command $Name -ErrorAction SilentlyContinue) {
        return
    }

    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "$DisplayName est absent et Windows Package Manager (winget) est indisponible."
    }

    Write-Host ""
    Write-Host "$DisplayName doit etre installe pour continuer." -ForegroundColor Yellow
    $answer = Read-Host "Installer automatiquement avec winget ? (O/N)"
    if ($answer -notmatch '^[OoYy]$') {
        throw "Installation de $DisplayName annulee."
    }

    & winget install --id $WingetId --exact --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "L'installation de $DisplayName a echoue."
    }

    Refresh-ProcessPath
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$DisplayName est installe, mais Windows doit etre redemarre avant de continuer."
    }
}

try {
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host " TaoConnect v0.2 - Compilation automatique de l'APK" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkCyan

    Ensure-Command -Name "git" -WingetId "Git.Git" -DisplayName "Git"
    Ensure-Command -Name "gh" -WingetId "GitHub.cli" -DisplayName "GitHub CLI"

    $authStatus = Invoke-NativeCapture -FilePath "gh" -Arguments @(
        "auth", "status", "--hostname", "github.com"
    )
    if ($authStatus.ExitCode -ne 0) {
        Write-Host ""
        Write-Host "Connexion GitHub : votre navigateur va s'ouvrir." -ForegroundColor Cyan
        Write-Host "Connectez-vous uniquement sur la page officielle GitHub." -ForegroundColor Cyan
        $loginExitCode = Invoke-NativeInteractive -FilePath "gh" -Arguments @(
            "auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--web"
        )
        if ($loginExitCode -ne 0) {
            throw "La connexion GitHub n'a pas abouti."
        }
    }

    $loginResult = Invoke-NativeCapture -FilePath "gh" -Arguments @(
        "api", "user", "--jq", ".login"
    )
    $login = $loginResult.Output
    if ($loginResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($login)) {
        throw "Impossible d'identifier le compte GitHub connecte."
    }

    if (-not (Test-Path (Join-Path $ProjectDir ".git"))) {
        & git init -b main
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible d'initialiser le depot local."
        }
    }

    $gitUser = (Invoke-NativeCapture -FilePath "git" -Arguments @(
        "config", "user.name"
    )).Output
    if ([string]::IsNullOrWhiteSpace($gitUser)) {
        & git config user.name "TaoConnect Builder"
    }
    $gitEmail = (Invoke-NativeCapture -FilePath "git" -Arguments @(
        "config", "user.email"
    )).Output
    if ([string]::IsNullOrWhiteSpace($gitEmail)) {
        & git config user.email "taoconnect@users.noreply.github.com"
    }

    & git add .
    & git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) {
        & git commit -m "Build TaoConnect Android connector v0.2"
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de creer le commit TaoConnect."
        }
    }
    & git branch -M main

    $originResult = Invoke-NativeCapture -FilePath "git" -Arguments @(
        "remote", "get-url", "origin"
    )
    if ($originResult.ExitCode -eq 0) {
        $originUrl = $originResult.Output
    }
    else {
        $originUrl = ""
    }

    if (-not [string]::IsNullOrWhiteSpace($originUrl)) {
        $repoResult = Invoke-NativeCapture -FilePath "gh" -Arguments @(
            "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"
        )
        $repoFullName = $repoResult.Output
        if ($repoResult.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($repoFullName)) {
            throw "Le depot distant configure n'est pas accessible avec ce compte GitHub."
        }
        & git push --set-upstream origin main
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible d'envoyer la mise a jour vers GitHub."
        }
    }
    else {
        $repoName = "taoconnect-android"
        $repoFullName = "$login/$repoName"
        $existingRepo = Invoke-NativeCapture -FilePath "gh" -Arguments @(
            "repo", "view", $repoFullName, "--json", "name"
        )

        if ($existingRepo.ExitCode -eq 0) {
            Write-Host ""
            Write-Host "Depot existant detecte : $repoFullName" -ForegroundColor Cyan
            & git remote add origin "https://github.com/$repoFullName.git"
            if ($LASTEXITCODE -ne 0) {
                throw "Impossible de relier le projet au depot GitHub existant."
            }

            & git fetch origin main
            if ($LASTEXITCODE -ne 0) {
                throw "Impossible de recuperer l'etat actuel du depot GitHub."
            }

            & git merge origin/main --allow-unrelated-histories --no-edit
            if ($LASTEXITCODE -ne 0) {
                throw "La fusion avec le depot GitHub a echoue. Aucun fichier distant n'a ete supprime."
            }

            & git push --set-upstream origin main
            if ($LASTEXITCODE -ne 0) {
                throw "Impossible d'envoyer TaoConnect vers GitHub."
            }
        }
        else {
            Write-Host ""
            Write-Host "Creation du depot prive $repoFullName..." -ForegroundColor Cyan
            & gh repo create $repoFullName --private --source $ProjectDir --remote origin --push
            if ($LASTEXITCODE -ne 0) {
                throw "La creation du depot prive GitHub a echoue."
            }
        }
    }

    Write-Host ""
    Write-Host "Recherche de la compilation GitHub Actions..." -ForegroundColor Cyan
    $run = $null
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 5
        $json = ((& gh run list --repo $repoFullName --workflow build-apk.yml --limit 1 --json databaseId,status,conclusion,createdAt 2>$null) | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($json)) {
            $items = $json | ConvertFrom-Json
            if (@($items).Count -gt 0) {
                $run = @($items)[0]
            }
        }
    } while ($null -eq $run -and (Get-Date) -lt $deadline)

    if ($null -eq $run) {
        throw "Aucune compilation GitHub Actions n'a demarre dans le delai prevu."
    }

    Write-Host "Compilation en cours. Cette etape peut durer plusieurs minutes..." -ForegroundColor Cyan
    & gh run watch $run.databaseId --repo $repoFullName --exit-status
    if ($LASTEXITCODE -ne 0) {
        $logPath = Join-Path $ProjectDir "TaoConnect-erreurs-compilation.txt"
        & gh run view $run.databaseId --repo $repoFullName --log-failed 2>&1 |
            Tee-Object -FilePath $logPath
        throw "La compilation a echoue. Le diagnostic est disponible dans $logPath"
    }

    $outputDir = Join-Path $ProjectDir "APK"
    if (Test-Path $outputDir) {
        $outputDir = Join-Path $ProjectDir "APK-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    }
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

    & gh run download $run.databaseId --repo $repoFullName --dir $outputDir
    if ($LASTEXITCODE -ne 0) {
        throw "La compilation a reussi, mais le telechargement de l'APK a echoue."
    }

    $apk = Get-ChildItem -Path $outputDir -Filter "*.apk" -File -Recurse |
        Select-Object -First 1
    if ($null -eq $apk) {
        throw "La compilation a reussi, mais aucun fichier APK n'a ete trouve."
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host " APK TAOCONNECT PRET" -ForegroundColor Green
    Write-Host " $($apk.FullName)" -ForegroundColor Green
    Write-Host " Depot prive : https://github.com/$repoFullName" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green

    Start-Process explorer.exe -ArgumentList "/select,`"$($apk.FullName)`""
    Read-Host "Appuyez sur Entree pour fermer"
}
catch {
    Write-Host ""
    Write-Host "ECHEC : $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Le projet n'a pas ete supprime. Vous pouvez relancer ce fichier." -ForegroundColor Yellow
    Read-Host "Appuyez sur Entree pour fermer"
    exit 1
}
