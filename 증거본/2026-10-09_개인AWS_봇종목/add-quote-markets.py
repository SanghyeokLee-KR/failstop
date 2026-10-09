import pathlib,re,json,urllib.request,datetime,shutil
p=pathlib.Path('/etc/exchange-bot/bot.env')
s=p.read_text()
d=json.load(urllib.request.urlopen('https://www.sang.it.kr/api/v1/ticker?markets=KRW-BTC,KRW-ETH,KRW-XRP',timeout=15))['data']
prices={x['market']:x['trade_price'] for x in d}
assert all(int(prices[m])>0 for m in ['KRW-BTC','KRW-ETH','KRW-XRP'])
backup=str(p)+'.before-quote-markets-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
shutil.copy2(p,backup)
for i,m in enumerate(['KRW-BTC','KRW-ETH','KRW-XRP']):
 s=re.sub(r'^EXCHANGE_SIM_MARKETS_'+str(i)+r'_INITIALPRICE=.*$', 'EXCHANGE_SIM_MARKETS_'+str(i)+'_INITIALPRICE='+prices[m],s,flags=re.M)
s=re.sub(r'^EXCHANGE_SIM_TARGET_OPS=.*$','EXCHANGE_SIM_TARGET_OPS=6',s,flags=re.M)
new=[('SOL','200000','0.1','0.01','1000'),('DOGE','200','50','1','1000000'),('ADA','1000','10','1','200000'),('TRX','300','30','1','500000')]
assert not re.search(r'^EXCHANGE_SIM_MARKETS_[3-6]_',s,re.M),'Already configured; inspect first'
for i,(sym,price,qty,step,funds) in enumerate(new,3):
 vals={'SYMBOL':'KRW-'+sym,'INITIALPRICE':price,'MMCOUNT':'1','TAKERCOUNT':'0','TRENDCOUNT':'0','NOISECOUNT':'0','QUOTESIZE':qty,'QTYSTEP':step,'BASEFUNDING':funds}
 s+='\n'.join('EXCHANGE_SIM_MARKETS_'+str(i)+'_'+k+'='+v for k,v in vals.items())+'\n'
p.write_text(s)
print('Backup:',backup)
print('Configured: BTC/ETH/XRP unchanged counts; SOL/DOGE/ADA/TRX one market maker each; total OPS 6')
