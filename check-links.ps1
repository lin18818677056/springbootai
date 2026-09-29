Set-Location "D:\mywork\springbootai\growth-plan"
$files = Get-ChildItem -Recurse -Include *.md
$total = 0
$dead = 0
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName)
    $ms = [regex]::Matches($text, '\]\(([^)#]+?\.md)\)')
    foreach ($m in $ms) {
        $total++
        $target = [System.Uri]::UnescapeDataString($m.Groups[1].Value)
        $full = Join-Path $f.DirectoryName $target
        if (-not (Test-Path $full)) {
            $dead++
            Write-Output ("DEAD: " + $f.Name + " -> " + $target)
        }
    }
}
Write-Output ("files: " + $files.Count + " / links: " + $total + " / dead links: " + $dead)
