<?php

declare(strict_types=1);

if (!defined('ABSPATH')) {
    exit;
}

if (!defined('EP_SEARCH_ALIAS') || EP_SEARCH_ALIAS === '') {
    return;
}

/**
 * Route public search queries to a read alias that can span multiple indices.
 *
 * This keeps write/index operations isolated per context while exposing a
 * single logical search target to WordPress.
 */
$n9_ep_alias_filter = static function (
    string $path,
    string $index,
    string $type,
    array $query,
    array $queryArgs,
    $queryObject = null
): string {
    if (empty($queryArgs['s'])) {
        return $path;
    }

    return EP_SEARCH_ALIAS . '/_search';
};

add_filter('ep_search_request_path', $n9_ep_alias_filter, 10, 5);
add_filter('ep_query_request_path', $n9_ep_alias_filter, 10, 5);
