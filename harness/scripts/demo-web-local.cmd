@echo off
rem Local demo web. Next.js dev server on port 3001 behind the admin gateway (chaos-admin-gw).
rem Open http://localhost:3000 in the browser, not 3001. The gateway serves this web and the admin API on one origin.
rem Trading API/WS go straight to chaos-lb (58080). The API dev profile allows only the http://localhost:3000 origin.
rem For AWS the web is the deployed build (deploy/linux/app/build-web.ps1), so this script is local only.
set NEXT_PUBLIC_DATASOURCE=http
set NEXT_PUBLIC_API_BASE=http://localhost:58080
set NEXT_PUBLIC_WS_BASE=ws://localhost:58080
set NEXT_PUBLIC_ADMIN_DATASOURCE=http
set NEXT_PUBLIC_ADMIN_AUTH=password
set NEXT_PUBLIC_ADMIN_BASE=http://localhost:3000
set NEXT_PUBLIC_GRAFANA_BASE=http://localhost:53001
cd /d "%~dp0..\.."
npm run dev --prefix web -- -p 3001
