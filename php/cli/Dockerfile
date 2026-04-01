FROM php:8.2.28-cli-alpine

RUN set -eux; \
    apk add --no-cache --virtual .build-deps $PHPIZE_DEPS; \
    apk add --no-cache bash curl less mariadb-client; \
    docker-php-ext-install mysqli pdo_mysql exif; \
    apk del .build-deps; \
    curl -fsSL https://raw.githubusercontent.com/wp-cli/builds/gh-pages/phar/wp-cli.phar -o /usr/local/bin/wp; \
    chmod +x /usr/local/bin/wp; \
    wp --allow-root --info >/dev/null
