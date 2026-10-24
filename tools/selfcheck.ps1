<#
.SYNOPSIS
    重构/提交前的统一自检。任何一步失败就停下，不要继续往下做。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools/selfcheck.ps1
    powershell -ExecutionPolicy Bypass -File tools/selfcheck.ps1 -Full
#>
param(
    [switch]$Full   # -Full 额外跑较慢的协议/资源类测试
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step($name, $block) {
    Write-Host "`n=== $name ===" -ForegroundColor Cyan
    & $block
    if ($LASTEXITCODE -ne 0) {
        Write-Host "✗ $name 失败（exit=$LASTEXITCODE），停止。" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host "✓ $name" -ForegroundColor Green
}

Step 'compileJava' { .\gradlew.bat --no-daemon --max-workers=1 -q compileJava }

# 必跑：覆盖「配置键 ↔ 云端」「mixin 清单」「Verity 结构探测」「mixin 注入目标是否真实存在」
# 🔴 pluginTest 已随插件系统在 5.0 移除，换成 mixinTargetProbeTest——
#    后者守的是「@Inject 目标方法不存在」这类只在运行期才现形的问题，比插件测试重要得多。
Step 'cloudOverridesTest'          { .\gradlew.bat --no-daemon --max-workers=1 -q cloudOverridesTest }
Step 'mixinPackageHygieneTest'     { .\gradlew.bat --no-daemon --max-workers=1 -q mixinPackageHygieneTest }
Step 'mixinTargetProbeTest'        { .\gradlew.bat --no-daemon --max-workers=1 -q mixinTargetProbeTest }
Step 'verityStructureDetectorTest' { .\gradlew.bat --no-daemon --max-workers=1 -q verityStructureDetectorTest }

if ($Full) {
    Step 'cloudProtocolTest'        { .\gradlew.bat --no-daemon --max-workers=1 -q cloudProtocolTest }
    Step 'resourceCollisionTest'    { .\gradlew.bat --no-daemon --max-workers=1 -q resourceCollisionTest }
    Step 'verityLocalizationTest'   { .\gradlew.bat --no-daemon --max-workers=1 -q verityLocalizationTest }
    Step 'ttsRoutePlannerTest'      { .\gradlew.bat --no-daemon --max-workers=1 -q ttsRoutePlannerTest }
    Step 'verityHorrorDirectorTest' { .\gradlew.bat --no-daemon --max-workers=1 -q verityHorrorDirectorTest }
    Step 'rideKeyTest'              { .\gradlew.bat --no-daemon --max-workers=1 -q rideKeyTest }
    Step 'wanderTest'               { .\gradlew.bat --no-daemon --max-workers=1 -q wanderTest }
    Step 'veritySupportTest'        { .\gradlew.bat --no-daemon --max-workers=1 -q veritySupportTest }
    Step 'combatAssistTest'         { .\gradlew.bat --no-daemon --max-workers=1 -q combatAssistTest }
}

Write-Host "`n全部通过。" -ForegroundColor Green
