const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const path = require('node:path');
const context = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../static-routes.js'), 'utf8'), context);
test('export paths resolve while assets and query strings remain intact', () => {
    for (const [input, output] of [['/', '/index.html'], ['/trade', '/trade.html'], ['/trade/', '/trade.html'], ['/_next/static/main.js', '/_next/static/main.js'], ['/favicon.ico', '/favicon.ico']]) {
        const request = {uri: input, querystring: {market: {value: 'BTC-KRW'}}};
        assert.equal(context.handler({request}).uri, output);
        assert.equal(request.querystring.market.value, 'BTC-KRW');
    }
});
