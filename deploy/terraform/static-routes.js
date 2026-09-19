// Next.js export는 기본 설정에서 /trade를 trade.html로 만든다.
function handler(event) {
    var request = event.request;
    var uri = request.uri;
    if (uri === "/") {
        request.uri = "/index.html";
    } else {
        if (uri.endsWith("/")) uri = uri.slice(0, -1);
        if (uri.split("/").pop().indexOf(".") === -1) uri += ".html";
        request.uri = uri;
    }
    return request;
}
