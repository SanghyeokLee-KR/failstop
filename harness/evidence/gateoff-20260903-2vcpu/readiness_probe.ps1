param([string]$Out,[int]$Rounds=280,[int]$Port=8090)
# ALB 헬스체크와 같은 조건으로 readiness 를 두드린다(타임아웃 5초). ALB 기본은 간격 30초,
# 타임아웃 5초, 비정상 임계 2회다. 여기서는 3초 간격으로 촘촘히 재서 전이 시각을 좁힌다.
't,code,ms,body' | Out-File -FilePath $Out -Encoding utf8
for ($i=0; $i -lt $Rounds; $i++) {
  $sw=[Diagnostics.Stopwatch]::StartNew(); $code=0; $body=''
  try { $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health/readiness" -UseBasicParsing -TimeoutSec 5; $code=$r.StatusCode; $body=($r.Content -replace '[",]','' -replace 'status:?','') }
  catch { if ($_.Exception.Response) { $code=[int]$_.Exception.Response.StatusCode; $body='ERRSTATUS' } else { $code=0; $body='TIMEOUT' } }
  $sw.Stop()
  "$(Get-Date -Format 'HH:mm:ss'),$code,$([int]$sw.ElapsedMilliseconds),$body" | Out-File -FilePath $Out -Append -Encoding utf8
  Start-Sleep -Seconds 3
}
