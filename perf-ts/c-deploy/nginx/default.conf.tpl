# deploy 스크립트가 __ACTIVE__ 를 api-blue 또는 api-green 으로 바꿔 conf.d/default.conf 로 저장한다.
upstream api_upstream {
    server __ACTIVE__:8080;
    keepalive 16;
}

server {
    listen 80;

    location / {
        proxy_pass http://api_upstream;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
