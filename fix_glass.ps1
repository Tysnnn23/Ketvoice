$fix = @'
    params: GlassParams,
    enabled: Boolean = true,
    captured: Boolean = false,
    content: @Composable () -> Unit
'@ -replace "`n", "`r`n"
$old = @'
    params: GlassParams,
    enabled: Boolean = true,
    content: @Composable () -> Unit
'@ -replace "`n", "`r`n"
$p='C:\Users\Yanis\Downloads\Ketvoice\app\src\main\kotlin\com\ketvoice\app\glass\Glass.kt'
$c=[IO.File]::ReadAllText((Resolve-Path $p))
$c=$c.Replace($old,$fix)

$c=$c.Replace('if (backdrop != null && enabled && !AppSettings.reduceDynamicBlur.value &&','if (backdrop != null && enabled && !captured && !AppSettings.reduceDynamicBlur.value &&')
[IO.File]::WriteAllText((Resolve-Path $p),$c)
Select-String -Path $p -Pattern 'captured'
