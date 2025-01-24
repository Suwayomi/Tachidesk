# uninstall previous versions that do not have the same update code via the product name

$productName = "Suwayomi Server"

# Get the product code of the specified product
$productInfo = @(Get-WmiObject -Class Win32_Product | Where-Object { $_.Name -eq $productName })

$productCount = $productInfo.Count
Write-Output "Found $productCount installations"

# Uninstall the product
if ($productCount -gt 0) {

    $productInfo | ForEach-Object {
        $installedVersion = "$($_.Name) ($($_.IdentifyingNumber))"
        Write-Output "Uninstalling version: $($installedVersion)"
        $uninstallResult = $_.Uninstall()
        if ($uninstallResult.ReturnValue -eq 0) {
            Write-Output "Successfully uninstalled $($installedVersion)"
        } else {
            Write-Output "Failed to uninstall $($installedVersion)"
        }
    }
}
