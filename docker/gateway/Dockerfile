FROM nginx:1.28-alpine
RUN apk add --no-cache apache2-utils
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY proxy_params_livecontext /etc/nginx/proxy_params_livecontext
COPY 15-bootstrap-auth.sh /docker-entrypoint.d/15-bootstrap-auth.sh
RUN chmod 755 /docker-entrypoint.d/15-bootstrap-auth.sh
