FROM php:8.2.28-fpm-alpine

RUN set -eux; \
    apk add --no-cache --virtual .build-deps $PHPIZE_DEPS; \
    docker-php-ext-install mysqli pdo_mysql exif; \
    apk del .build-deps

RUN echo 'include=etc/php-fpm.d/zzz-project/*.conf' >> /usr/local/etc/php-fpm.conf

COPY php/common/zz-security.ini /usr/local/etc/php/conf.d/zz-security.ini
