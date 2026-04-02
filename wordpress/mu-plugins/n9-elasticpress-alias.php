<?php

declare(strict_types=1);

if (!defined('ABSPATH')) {
    exit;
}

if (!defined('EP_SEARCH_ALIAS') || EP_SEARCH_ALIAS === '') {
    return;
}

$n9_ep_resolve_search_permalink = static function (\WP_Post $post): string {
    foreach (['permalink', 'guid'] as $property) {
        if (!isset($post->{$property}) || !is_string($post->{$property})) {
            continue;
        }

        $candidate = trim($post->{$property});
        if ($candidate === '' || !wp_http_validate_url($candidate)) {
            continue;
        }

        return $candidate;
    }

    if (
        isset($post->post_name, $post->post_date) &&
        is_string($post->post_name) &&
        $post->post_name !== '' &&
        is_string($post->post_date) &&
        $post->post_date !== ''
    ) {
        $timestamp = strtotime($post->post_date);
        if ($timestamp !== false) {
            return home_url(sprintf(
                '/%s/%s/%s/%s/',
                gmdate('Y', $timestamp),
                gmdate('m', $timestamp),
                gmdate('d', $timestamp),
                $post->post_name
            ));
        }
    }

    return '';
};

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

add_filter(
    'render_block_context',
    static function (array $context, array $parsedBlock) use ($n9_ep_resolve_search_permalink): array {
        if (
            !is_search() ||
            empty($context['postId']) ||
            !isset($parsedBlock['blockName']) ||
            !in_array($parsedBlock['blockName'], ['core/post-title', 'core/post-date'], true)
        ) {
            return $context;
        }

        global $post;

        if (!$post instanceof \WP_Post) {
            return $context;
        }

        if ($n9_ep_resolve_search_permalink($post) === '') {
            return $context;
        }

        $context['postId'] = $post;

        return $context;
    },
    20,
    2
);

add_filter(
    'the_posts',
    static function (array $posts, \WP_Query $query) use ($n9_ep_resolve_search_permalink): array {
        if (!$query->is_search()) {
            return $posts;
        }

        foreach ($posts as $post) {
            if (!$post instanceof \WP_Post) {
                continue;
            }

            $resolved = $n9_ep_resolve_search_permalink($post);
            if ($resolved === '') {
                continue;
            }

            $post->permalink = $resolved;

            if (!isset($post->guid) || !is_string($post->guid) || trim($post->guid) === '') {
                $post->guid = $resolved;
            }
        }

        return $posts;
    },
    10,
    2
);

/**
 * When ElasticPress hydrates results coming from the archive index, some block
 * render paths can still fall back to plain `?p=<id>` links. Reuse the indexed
 * permalink when present so mixed live/archive search results keep canonical
 * dated URLs.
 */
$n9_ep_permalink_filter = static function (string $permalink, \WP_Post $post) use ($n9_ep_resolve_search_permalink): string {
    if (!is_search()) {
        return $permalink;
    }

    $resolved = $n9_ep_resolve_search_permalink($post);
    if ($resolved === '') {
        return $permalink;
    }

    return $resolved;
};

add_filter('post_link', $n9_ep_permalink_filter, 10, 2);
add_filter(
    'post_type_link',
    static function (string $permalink, \WP_Post $post) use ($n9_ep_resolve_search_permalink): string {
        if (!is_search()) {
            return $permalink;
        }

        $resolved = $n9_ep_resolve_search_permalink($post);
        if ($resolved === '') {
            return $permalink;
        }

        return $resolved;
    },
    10,
    2
);

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

        global $n9_ep_resolve_search_permalink;

        if (!$n9_ep_resolve_search_permalink instanceof \Closure) {
            return $permalink;
        }

        $resolved = $n9_ep_resolve_search_permalink($post);
        if ($resolved === '') {
            return $permalink;
        }

        return $resolved;
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

        global $n9_ep_resolve_search_permalink;

        if (!$n9_ep_resolve_search_permalink instanceof \Closure) {
            return $blockContent;
        }

        $resolved = $n9_ep_resolve_search_permalink($post);
        if ($resolved === '') {
            return $blockContent;
        }

        return (string) preg_replace(
            '/href="[^"]+"/',
            'href="' . esc_url($resolved) . '"',
            $blockContent,
            1
        );
    },
    10,
    2
);

$n9_ep_patch_empty_search_href = static function (string $blockContent) use ($n9_ep_resolve_search_permalink): string {
    if (!is_search() || !str_contains($blockContent, 'href=""')) {
        return $blockContent;
    }

    global $post;

    if (!$post instanceof \WP_Post) {
        return $blockContent;
    }

    $resolved = $n9_ep_resolve_search_permalink($post);
    if ($resolved === '') {
        return $blockContent;
    }

    return (string) preg_replace(
        '/href=""/',
        'href="' . esc_url($resolved) . '"',
        $blockContent,
        1
    );
};

add_filter(
    'render_block_core/post-title',
    static function (string $blockContent) use ($n9_ep_patch_empty_search_href): string {
        return $n9_ep_patch_empty_search_href($blockContent);
    },
    10,
    1
);

add_filter(
    'render_block_core/post-date',
    static function (string $blockContent) use ($n9_ep_patch_empty_search_href): string {
        return $n9_ep_patch_empty_search_href($blockContent);
    },
    10,
    1
);

add_action(
    'template_redirect',
    static function () use ($n9_ep_resolve_search_permalink): void {
        if (!is_search() || is_admin() || !class_exists(\DOMDocument::class)) {
            return;
        }

        global $wp_query;

        if (!$wp_query instanceof \WP_Query || empty($wp_query->posts) || !is_array($wp_query->posts)) {
            return;
        }

        $permalink_map = [];
        foreach ($wp_query->posts as $queried_post) {
            if (!$queried_post instanceof \WP_Post) {
                continue;
            }

            $resolved = $n9_ep_resolve_search_permalink($queried_post);
            if ($resolved === '') {
                continue;
            }

            $permalink_map[(int) $queried_post->ID] = $resolved;
        }

        if ($permalink_map === []) {
            return;
        }

        ob_start(
            static function (string $html) use ($permalink_map): string {
                if (!str_contains($html, 'href=""')) {
                    return $html;
                }

                $previous = libxml_use_internal_errors(true);
                $dom = new \DOMDocument('1.0', 'UTF-8');
                $loaded = $dom->loadHTML($html, LIBXML_HTML_NOIMPLIED | LIBXML_HTML_NODEFDTD);

                if (!$loaded) {
                    libxml_clear_errors();
                    libxml_use_internal_errors($previous);
                    return $html;
                }

                $xpath = new \DOMXPath($dom);
                $nodes = $xpath->query(
                    '//*[contains(concat(" ", normalize-space(@class), " "), " wp-block-post-title ") or contains(concat(" ", normalize-space(@class), " "), " wp-block-post-date ")]//a[@href=""]'
                );

                if ($nodes instanceof \DOMNodeList) {
                    foreach ($nodes as $node) {
                        $current = $node;
                        $resolved = '';

                        while ($current instanceof \DOMElement) {
                            $class = $current->getAttribute('class');
                            if ($class !== '' && preg_match('/\bpost-(\d+)\b/', $class, $matches) === 1) {
                                $resolved = $permalink_map[(int) $matches[1]] ?? '';
                                break;
                            }

                            $parent = $current->parentNode;
                            $current = $parent instanceof \DOMElement ? $parent : null;
                        }

                        if ($resolved !== '') {
                            $node->setAttribute('href', $resolved);
                        }
                    }
                }

                $result = $dom->saveHTML() ?: $html;
                libxml_clear_errors();
                libxml_use_internal_errors($previous);

                return $result;
            }
        );
    },
    0
);
