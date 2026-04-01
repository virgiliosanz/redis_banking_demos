FROM php:8.2.28-fpm-alpine

RUN set -eux; \
    apk add --no-cache --virtual .build-deps $PHPIZE_DEPS; \
    docker-php-ext-install mysqli pdo_mysql exif; \
    apk del .build-deps
