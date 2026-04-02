<?php

$target_year = getenv('ROLLOVER_TARGET_YEAR');
if ($target_year === false || !preg_match('/^20\d{2}$/', $target_year)) {
    fwrite(STDERR, "Invalid or missing ROLLOVER_TARGET_YEAR\n");
    exit(1);
}

$query = new WP_Query([
    'post_type' => ['post', 'attachment'],
    'post_status' => ['publish', 'inherit'],
    'posts_per_page' => -1,
    'orderby' => 'date',
    'order' => 'ASC',
    'fields' => 'ids',
    'date_query' => [
        [
            'year' => (int) $target_year,
        ],
    ],
]);

$candidate_ids = array_map('intval', $query->posts);
$posts = [];
$attachments = [];
$term_registry = [];
$attachment_ids = [];

$is_excluded_meta = static function (string $meta_key): bool {
    if (in_array($meta_key, ['_edit_lock', '_edit_last'], true)) {
        return true;
    }

    foreach (['_wp_old_', '_wp_trash_'] as $prefix) {
        if (str_starts_with($meta_key, $prefix)) {
            return true;
        }
    }

    return false;
};

foreach ($candidate_ids as $post_id) {
    $post = get_post($post_id);
    if (!$post instanceof WP_Post) {
        continue;
    }

    if ($post->post_type === 'attachment') {
        $attachment_ids[$post_id] = true;
        continue;
    }

    if ($post->post_type !== 'post' || $post->post_status !== 'publish') {
        continue;
    }

    $post_terms = [];
    $terms = wp_get_object_terms($post_id, ['category', 'post_tag']);
    if (!is_wp_error($terms)) {
        foreach ($terms as $term) {
            $term_registry[$term->taxonomy . ':' . $term->slug] = [
                'taxonomy' => $term->taxonomy,
                'name' => $term->name,
                'slug' => $term->slug,
            ];
            $post_terms[] = [
                'taxonomy' => $term->taxonomy,
                'slug' => $term->slug,
            ];
        }
    }

    $meta = [];
    foreach (get_post_meta($post_id) as $meta_key => $values) {
        if ($is_excluded_meta($meta_key) || !is_array($values)) {
            continue;
        }
        $meta[$meta_key] = array_map('maybe_unserialize', $values);
    }

    $thumbnail_id = (int) get_post_meta($post_id, '_thumbnail_id', true);
    if ($thumbnail_id > 0) {
        $attachment_ids[$thumbnail_id] = true;
    }

    $author = get_userdata((int) $post->post_author);

    $posts[] = [
        'source_post_id' => $post_id,
        'post_type' => $post->post_type,
        'post_status' => $post->post_status,
        'post_title' => $post->post_title,
        'post_name' => $post->post_name,
        'post_date' => $post->post_date,
        'post_excerpt' => $post->post_excerpt,
        'post_content' => $post->post_content,
        'author_login' => $author instanceof WP_User ? $author->user_login : '',
        'terms' => $post_terms,
        'meta' => $meta,
        'permalink' => get_permalink($post_id),
    ];
}

foreach (array_keys($attachment_ids) as $attachment_id) {
    $attachment = get_post((int) $attachment_id);
    if (!$attachment instanceof WP_Post || $attachment->post_type !== 'attachment') {
        continue;
    }

    $meta = [];
    foreach (get_post_meta($attachment_id) as $meta_key => $values) {
        if ($is_excluded_meta($meta_key) || !is_array($values)) {
            continue;
        }
        $meta[$meta_key] = array_map('maybe_unserialize', $values);
    }

    $attachments[] = [
        'source_post_id' => (int) $attachment_id,
        'post_title' => $attachment->post_title,
        'post_name' => $attachment->post_name,
        'post_date' => $attachment->post_date,
        'post_excerpt' => $attachment->post_excerpt,
        'post_content' => $attachment->post_content,
        'post_mime_type' => $attachment->post_mime_type,
        'guid' => $attachment->guid,
        'meta' => $meta,
    ];
}

$result = [
    'site' => home_url(),
    'target_year' => (int) $target_year,
    'posts' => $posts,
    'terms' => array_values($term_registry),
    'attachments' => $attachments,
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
