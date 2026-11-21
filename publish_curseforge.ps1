<#
.SYNOPSIS
    Uploads all built AzureFrameLib JARs across active Minecraft versions directly to CurseForge.

.DESCRIPTION
    1. Prompts you interactively to paste your Markdown changelog (or grab it straight from your clipboard).
    2. Reads CURSEFORGE_TOKEN and CURSEFORGE_PROJECT_ID from curseforge.local (or environment variables).
    3. Queries CurseForge's Upload API for exact Minecraft / Loader / Java / Environment IDs.
    4. Previews all 20 JARs and your changelog, asks for confirmation, and uploads each JAR to CurseForge.

.EXAMPLE
    .\publish_curseforge.ps1
    Prompts you to paste a changelog, shows the preview, confirms, and uploads all 20 JARs.

.EXAMPLE
    .\publish_curseforge.ps1 -DryRun
    Lets you test pasting a changelog (or pass -Changelog "Test") and previews all 20 JARs without uploading.

.EXAMPLE
    .\publish_curseforge.ps1 -ReleaseType release
    Overrides automatic release type detection ('a'=alpha, 'b'=beta) to 'release'.
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Yes,
    [ValidateSet("auto", "alpha", "beta", "release")]
    [string]$ReleaseType = "auto",
    [string[]]$MinecraftVersions = @(),
    [string]$BuildNumberOverride = "",
    [string]$ChangelogFile = "",
    [string]$Changelog = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RootDir = $PSScriptRoot
if (-not $RootDir) {
    $RootDir = (Get-Location).Path
}

if (-not $MinecraftVersions -or $MinecraftVersions.Count -eq 0) {
    $rootBg = Join-Path $RootDir "build.gradle"
    if ((Test-Path $rootBg) -and ((Get-Content -Raw $rootBg) -match "activeVersions\s*=\s*\[([^\]]+)\]")) {
        $MinecraftVersions = ($matches[1] -split ",") | ForEach-Object { $_.Trim().Trim('"').Trim("'") } | Where-Object { $_ }
    } else {
        $MinecraftVersions = Get-ChildItem -Path $RootDir -Directory |
            Where-Object { $_.Name -match "^(\d+\.\d+)" -and (Test-Path (Join-Path $_.FullName "gradle.properties")) } |
            Select-Object -ExpandProperty Name
    }
}

# ---------------------------------------------------------------------------
# 1. Load CurseForge Credentials (curseforge.local -> .env -> Environment)
# ---------------------------------------------------------------------------
$Config = @{}
foreach ($cfgFile in @("curseforge.local", ".env")) {
    $cfgPath = Join-Path $RootDir $cfgFile
    if (Test-Path $cfgPath) {
        Get-Content $cfgPath | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line -match "^([^=]+)=(.*)$") {
                $Config[$matches[1].Trim()] = $matches[2].Trim().Trim('"').Trim("'")
            }
        }
    }
}

$ApiToken = if ($env:CURSEFORGE_TOKEN) { $env:CURSEFORGE_TOKEN } elseif ($Config.ContainsKey("CURSEFORGE_TOKEN")) { $Config["CURSEFORGE_TOKEN"] } else { "" }
$ProjectId = if ($env:CURSEFORGE_PROJECT_ID) { $env:CURSEFORGE_PROJECT_ID } elseif ($Config.ContainsKey("CURSEFORGE_PROJECT_ID")) { $Config["CURSEFORGE_PROJECT_ID"] } else { "1688476" }

if (-not $ApiToken) {
    throw "Missing CURSEFORGE_TOKEN! Add CURSEFORGE_TOKEN=<your-token> to '$RootDir\curseforge.local' or set `$env:CURSEFORGE_TOKEN."
}

# ---------------------------------------------------------------------------
# 2. Read Shared Build Number
# ---------------------------------------------------------------------------
$BuildNumberPath = Join-Path $RootDir "build_number.txt"
if ($BuildNumberOverride) {
    $BuildNumStr = "{0:D3}" -f ([int]$BuildNumberOverride)
} elseif (Test-Path $BuildNumberPath) {
    $BuildNumStr = "{0:D3}" -f ([int](Get-Content -Raw $BuildNumberPath).Trim())
} else {
    throw "build_number.txt not found at $BuildNumberPath"
}

# ---------------------------------------------------------------------------
# 3. Helper: Parse gradle.properties & build.gradle Java version
# ---------------------------------------------------------------------------
function Read-GradleProperties([string]$FilePath) {
    $props = @{}
    Get-Content $FilePath | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([^=]+)=(.*)$") {
            $props[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    return $props
}

function Get-JavaVersionName([string]$VerDir) {
    $bgPath = Join-Path $VerDir "build.gradle"
    if (Test-Path $bgPath) {
        $bgText = Get-Content -Raw $bgPath
        if ($bgText -match "options\.release\s*=\s*(\d+)") {
            return "Java $($matches[1])"
        }
        if ($bgText -match "VERSION_(\d+)") {
            return "Java $($matches[1])"
        }
    }
    return "Java 21"
}

# ---------------------------------------------------------------------------
# 4. Query CurseForge Game Version Types & Version IDs
# ---------------------------------------------------------------------------
Write-Host "Querying CurseForge Upload API for valid Game Version IDs..." -ForegroundColor Cyan
$Headers = @{ "X-Api-Token" = $ApiToken }
$CfTypes = Invoke-RestMethod -Uri "https://minecraft.curseforge.com/api/game/version-types" -Headers $Headers -Method Get
$CfVersions = Invoke-RestMethod -Uri "https://minecraft.curseforge.com/api/game/versions" -Headers $Headers -Method Get

$McTypeIds = @{}
$LoaderTypeId = $null
$JavaTypeId = $null
$EnvTypeId = $null

foreach ($t in $CfTypes) {
    if ($t.slug -match "^minecraft-\d+") {
        $McTypeIds[[int]$t.id] = $t.slug
    } elseif ($t.slug -eq "modloader") {
        $LoaderTypeId = [int]$t.id
    } elseif ($t.slug -eq "java") {
        $JavaTypeId = [int]$t.id
    } elseif ($t.slug -eq "environment") {
        $EnvTypeId = [int]$t.id
    }
}

$McVersionMap = @{}
$LoaderMap = @{}
$JavaMap = @{}
$EnvMap = @{}

foreach ($v in $CfVersions) {
    $tid = [int]$v.gameVersionTypeID
    if ($McTypeIds.ContainsKey($tid)) {
        $McVersionMap[$v.name] = [int]$v.id
    } elseif ($tid -eq $LoaderTypeId) {
        $LoaderMap[$v.name.ToLowerInvariant()] = [int]$v.id
    } elseif ($tid -eq $JavaTypeId) {
        $JavaMap[$v.name] = [int]$v.id
    } elseif ($tid -eq $EnvTypeId) {
        $EnvMap[$v.name] = [int]$v.id
    }
}

# ---------------------------------------------------------------------------
# 5. Collect & Validate All Target JARs Before Prompting or Uploading
# ---------------------------------------------------------------------------
$UploadPlan = New-Object System.Collections.Generic.List[object]
$SampleBuildVer = $null

foreach ($verFolder in $MinecraftVersions) {
    $verDir = Join-Path $RootDir $verFolder
    if (-not (Test-Path $verDir)) {
        throw "Minecraft version directory not found: $verDir"
    }

    $props = Read-GradleProperties (Join-Path $verDir "gradle.properties")
    $modVersion = $props["mod_version"]
    $modTag = $props["mod_tag"]
    $mcVersion = $props["minecraft_version"]
    $archivesName = $props["archives_name"]
    $platforms = @("fabric", "forge", "neoforge") | Where-Object { Test-Path (Join-Path $verDir $_) }

    $buildVer = "${modVersion}-b${BuildNumStr}${modTag}"
    if (-not $SampleBuildVer) { $SampleBuildVer = $buildVer }

    # Determine release type ('auto' maps tag 'a' -> alpha, 'b' -> beta, else release)
    $resolvedReleaseType = if ($ReleaseType -ne "auto") {
        $ReleaseType
    } elseif ($modTag -eq "a") {
        "alpha"
    } elseif ($modTag -eq "b") {
        "beta"
    } else {
        "release"
    }

    # Resolve Minecraft version IDs (for 26.1.x, tag 26.1, 26.1.1, and 26.1.2 since mod accepts >=26.1)
    $mcNames = New-Object System.Collections.Generic.List[string]
    if ($verFolder -eq "26.1") {
        foreach ($candidate in @("26.1", "26.1.1", "26.1.2", $mcVersion)) {
            if ($McVersionMap.ContainsKey($candidate) -and -not $mcNames.Contains($candidate)) {
                $mcNames.Add($candidate)
            }
        }
    } else {
        if ($McVersionMap.ContainsKey($mcVersion)) {
            $mcNames.Add($mcVersion)
        } elseif ($McVersionMap.ContainsKey($verFolder)) {
            $mcNames.Add($verFolder)
        }
    }
    if ($mcNames.Count -eq 0) {
        throw "Could not resolve CurseForge Minecraft version ID for folder '$verFolder' (minecraft_version='$mcVersion')."
    }

    # Resolve Java version IDs
    $primaryJava = Get-JavaVersionName $verDir
    $javaNames = New-Object System.Collections.Generic.List[string]
    if ($JavaMap.ContainsKey($primaryJava)) {
        $javaNames.Add($primaryJava)
    }
    if ($primaryJava -eq "Java 17" -and $JavaMap.ContainsKey("Java 21")) {
        $javaNames.Add("Java 21")
    }

    foreach ($loader in $platforms) {
        $jarName = "${archivesName}-${loader}-${mcVersion}-${buildVer}.jar"
        $jarPath = Join-Path $verDir "${loader}\build\libs\${jarName}"
        if (-not (Test-Path $jarPath)) {
            throw "Missing expected built JAR: $jarPath`nRun builds first before publishing."
        }

        if (-not $LoaderMap.ContainsKey($loader.ToLowerInvariant())) {
            throw "Could not resolve CurseForge loader ID for '$loader'."
        }

        $gameVersionIds = New-Object System.Collections.Generic.List[int]
        $readableTags = New-Object System.Collections.Generic.List[string]

        foreach ($m in $mcNames) {
            $gameVersionIds.Add($McVersionMap[$m])
            $readableTags.Add("MC $m ($($McVersionMap[$m]))")
        }

        $loaderNames = New-Object System.Collections.Generic.List[string]
        $loaderNames.Add($loader.ToLowerInvariant())
        if ($loader -eq "fabric" -and $LoaderMap.ContainsKey("quilt")) {
            $loaderNames.Add("quilt")
        } elseif ($verFolder -eq "1.20.1" -and $loader -eq "forge" -and $LoaderMap.ContainsKey("neoforge")) {
            $loaderNames.Add("neoforge")
        }

        foreach ($ln in $loaderNames) {
            $lid = $LoaderMap[$ln]
            $gameVersionIds.Add($lid)
            $readableTags.Add("$ln ($lid)")
        }

        foreach ($j in $javaNames) {
            $gameVersionIds.Add($JavaMap[$j])
            $readableTags.Add("$j ($($JavaMap[$j]))")
        }

        foreach ($envName in @("Client", "Server")) {
            if ($EnvMap.ContainsKey($envName)) {
                $gameVersionIds.Add($EnvMap[$envName])
            }
        }

        # Build dependency relations
        # Standalone Forge (1.21, 1.21.1, 1.21.8, 1.21.11) does not require Architectury API
        $projects = New-Object System.Collections.Generic.List[object]
        $needsArchitectury = ($loader -ne "forge") -or ($verFolder -eq "1.20.1")
        if ($needsArchitectury) {
            $projects.Add(@{ slug = "architectury-api"; type = "requiredDependency" })
        }
        if ($loader -eq "fabric") {
            $projects.Add(@{ slug = "fabric-api"; type = "requiredDependency" })
        }
        $projects.Add(@{ slug = "geckolib"; type = "optionalDependency" })

        $UploadPlan.Add([PSCustomObject]@{
            VersionFolder = $verFolder
            Loader        = $loader
            BuildVer      = $buildVer
            DisplayName   = $jarName
            JarPath       = $jarPath
            JarSizeKB     = [Math]::Round((Get-Item $jarPath).Length / 1KB, 1)
            ReleaseType   = $resolvedReleaseType
            GameVersions  = $gameVersionIds.ToArray()
            ReadableTags  = ($readableTags -join ", ")
            Relations     = $projects.ToArray()
        })
    }
}

# ---------------------------------------------------------------------------
# 6. Prompt User to Paste Changelog (Unless Passed via Parameter)
# ---------------------------------------------------------------------------
$ChangelogText = ""
$ChangelogSource = ""

if ($Changelog) {
    $ChangelogText = $Changelog.Trim()
    $ChangelogSource = "-Changelog parameter"
} elseif ($ChangelogFile) {
    if (-not (Test-Path $ChangelogFile)) {
        throw "Specified -ChangelogFile does not exist: $ChangelogFile"
    }
    $ChangelogText = (Get-Content -Raw $ChangelogFile).Trim()
    $ChangelogSource = $ChangelogFile
} else {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Yellow
    Write-Host " Enter Changelog for $SampleBuildVer (Markdown supported)" -ForegroundColor Yellow
    Write-Host "  - Paste your multi-line changelog below, then type END on a new" -ForegroundColor Gray
    Write-Host "    line and press Enter when finished (blank lines are kept)." -ForegroundColor Gray
    Write-Host "  - Or type CLIP and press Enter to grab whatever is currently" -ForegroundColor Gray
    Write-Host "    copied on your Windows clipboard." -ForegroundColor Gray
    Write-Host "==================================================================" -ForegroundColor Yellow

    $pastedLines = New-Object System.Collections.Generic.List[string]
    while ($true) {
        $line = [Console]::ReadLine()
        if ($null -eq $line) {
            break
        }
        if ($pastedLines.Count -eq 0 -and $line.Trim().ToUpperInvariant() -eq "CLIP") {
            $clipText = Get-Clipboard -Raw
            if (-not $clipText -or -not $clipText.Trim()) {
                Write-Host "Clipboard is empty! Please paste your changelog directly, then type END:" -ForegroundColor Red
                continue
            }
            $ChangelogText = $clipText.Trim()
            $ChangelogSource = "Windows Clipboard"
            break
        }
        if ($line.Trim().ToUpperInvariant() -eq "END") {
            $ChangelogText = ($pastedLines -join "`n").Trim()
            $ChangelogSource = "Interactive Paste"
            break
        }
        $pastedLines.Add($line)
    }

    if (-not $ChangelogText) {
        throw "Changelog cannot be empty. Aborting upload."
    }
}

# ---------------------------------------------------------------------------
# 7. Print Summary Table & Confirm Before Uploading
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==================================================================" -ForegroundColor Green
Write-Host " CurseForge Upload Plan - Project ID: $ProjectId" -ForegroundColor Green
Write-Host " Build Version : $SampleBuildVer ($($UploadPlan.Count) JARs)" -ForegroundColor Green
Write-Host " Release Type  : $($UploadPlan[0].ReleaseType)" -ForegroundColor Green
Write-Host " Changelog Src : $ChangelogSource" -ForegroundColor Green
Write-Host " Mode          : $(if ($DryRun) { 'DRY RUN (no files uploaded)' } else { 'LIVE UPLOAD' })" -ForegroundColor Yellow
Write-Host "==================================================================" -ForegroundColor Green

$idx = 0
foreach ($item in $UploadPlan) {
    $idx++
    $depList = ($item.Relations | ForEach-Object { "$($_.slug) [$($_.type -replace 'Dependency','')]" }) -join ", "
    Write-Host ("[{0,2}/{1}] {2} ({3} KB)" -f $idx, $UploadPlan.Count, $item.DisplayName, $item.JarSizeKB) -ForegroundColor White
    Write-Host ("        Tags : {0}" -f $item.ReadableTags) -ForegroundColor Gray
    Write-Host ("        Deps : {0}" -f $depList) -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "--- Changelog Preview ---" -ForegroundColor Cyan
Write-Host $ChangelogText
Write-Host "-------------------------" -ForegroundColor Cyan
Write-Host ""

if ($DryRun) {
    Write-Host "Dry run complete! All $($UploadPlan.Count) JARs and CurseForge IDs verified. Run without -DryRun to upload." -ForegroundColor Green
    exit 0
}

if (-not $Yes) {
    Write-Host -NoNewline "Push all $($UploadPlan.Count) JARs to CurseForge now? [y/N]: " -ForegroundColor Yellow
    $confirm = [Console]::ReadLine()
    if (-not $confirm -or $confirm.Trim().ToLowerInvariant() -notin @("y", "yes")) {
        Write-Host "Upload cancelled." -ForegroundColor Yellow
        exit 0
    }
}

# ---------------------------------------------------------------------------
# 8. Upload Each JAR via Multipart Form POST (Compatible with PS 5.1 & PS 7+)
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http

$HttpClient = New-Object System.Net.Http.HttpClient
$HttpClient.Timeout = [TimeSpan]::FromMinutes(5)
$HttpClient.DefaultRequestHeaders.Add("X-Api-Token", $ApiToken)

$UploadUrl = "https://minecraft.curseforge.com/api/projects/$ProjectId/upload-file"
$UploadedResults = New-Object System.Collections.Generic.List[object]

try {
    $idx = 0
    foreach ($item in $UploadPlan) {
        $idx++
        Write-Host ("[{0,2}/{1}] Uploading {2} ..." -f $idx, $UploadPlan.Count, $item.DisplayName) -ForegroundColor Cyan

        $metaObj = [ordered]@{
            changelog     = $ChangelogText
            changelogType = "markdown"
            displayName   = $item.DisplayName
            gameVersions  = $item.GameVersions
            releaseType   = $item.ReleaseType
            relations     = @{
                projects = $item.Relations
            }
        }
        $metaJson = $metaObj | ConvertTo-Json -Depth 6 -Compress

        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        $fileStream = [System.IO.File]::OpenRead($item.JarPath)
        try {
            $metaContent = New-Object System.Net.Http.StringContent($metaJson, [System.Text.Encoding]::UTF8, "application/json")
            $multipart.Add($metaContent, "metadata")

            $streamContent = New-Object System.Net.Http.StreamContent($fileStream)
            $streamContent.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/java-archive")
            $multipart.Add($streamContent, "file", $item.DisplayName)

            $response = $HttpClient.PostAsync($UploadUrl, $multipart).GetAwaiter().GetResult()
            $respBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()

            if (-not $response.IsSuccessStatusCode) {
                throw "HTTP $([int]$response.StatusCode) $($response.ReasonPhrase) uploading $($item.DisplayName): $respBody"
            }

            $respJson = $respBody | ConvertFrom-Json
            Write-Host ("        -> Uploaded! CurseForge File ID: {0}" -f $respJson.id) -ForegroundColor Green
            $UploadedResults.Add([PSCustomObject]@{
                FileName = $item.DisplayName
                FileId   = $respJson.id
            })
        } finally {
            $fileStream.Dispose()
            $multipart.Dispose()
        }

        # Gentle pacing between uploads so CurseForge doesn't rate-limit bursts
        Start-Sleep -Milliseconds 400
    }
} finally {
    $HttpClient.Dispose()
}

Write-Host ""
Write-Host "==================================================================" -ForegroundColor Green
Write-Host " Successfully uploaded all $($UploadedResults.Count) JARs to CurseForge!" -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Green
foreach ($res in $UploadedResults) {
    Write-Host ("  - {0} (ID: {1})" -f $res.FileName, $res.FileId)
}
