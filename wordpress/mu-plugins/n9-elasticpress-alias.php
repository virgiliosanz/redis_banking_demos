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

/**
 * When ElasticPress hydrates results coming from the archive index, some block
 * render paths can still fall back to plain `?p=<id>` links. Reuse the indexed
 * permalink when present so mixed live/archive search results keep canonical
 * dated URLs.
 */
$n9_ep_permalink_filter = static function (string $permalink, \WP_Post $post): string {
    if (!is_search()) {
        return $permalink;
    }

    if (!isset($post->permalink) || !is_string($post->permalink) || $post->permalink === '') {
        return $permalink;
    }

    return $post->permalink;
};

add_filter('post_link', $n9_ep_permalink_filter, 10, 2);

add_filter(
    'the_permalink',
    static function (string $permalink): string {
        if (!is_search()) {
            return $permalink;
        }

        global $post;

        if (!$post instanceof \WP_Post) {
            return $permalink;
        }

        if (!isset($post->permalink) || !is_string($post->permalink) || $post->permalink === '') {
            return $permalink;
        }

        return $post->permalink;
    }
);

add_filter(
    'render_block',
    static function (string $blockContent, array $block): string {
        if (!is_search()) {
            return $blockContent;
        }

        if (($block['blockName'] ?? '') !== 'core/post-title') {
            return $blockContent;
        }

        global $post;

        if (!$post instanceof \WP_Post) {
            return $blockContent;
        }

        if (!isset($post->permalink) || !is_string($post->permalink) || $post->permalink === '') {
            return $blockContent;
        }

        return (string) preg_replace(
            '/href="[^"]+"/',
            'href="' . esc_url($post->permalink) . '"',
            $blockContent,
            1
        );
    },
    10,
    2
);
