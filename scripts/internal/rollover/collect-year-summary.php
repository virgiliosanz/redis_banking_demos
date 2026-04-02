<?php

$target_year = getenv('ROLLOVER_TARGET_YEAR');
if ($target_year === false || !preg_match('/^20\d{2}$/', $target_year)) {
    fwrite(STDERR, "Invalid or missing ROLLOVER_TARGET_YEAR\n");
    exit(1);
}

$query = new WP_Query([
    'post_type' => 'post',
    'post_status' => 'publish',
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

$post_ids = array_map('intval', $query->posts);
$slugs = [];
$sample_urls = [];
$term_map = [];
$attachment_map = [];

foreach ($post_ids as $post_id) {
    $slugs[] = get_post_field('post_name', $post_id);
    $sample_urls[] = get_permalink($post_id);

    $terms = wp_get_object_terms($post_id, ['category', 'post_tag']);
    if (!is_wp_error($terms)) {
        foreach ($terms as $term) {
            $term_map[$term->taxonomy . ':' . $term->slug] = [
                'taxonomy' => $term->taxonomy,
                'slug' => $term->slug,
                'name' => $term->name,
            ];
        }
    }

    $thumbnail_id = (int) get_post_meta($post_id, '_thumbnail_id', true);
    if ($thumbnail_id > 0) {
        $attachment_map[$thumbnail_id] = [
            'id' => $thumbnail_id,
            'url' => wp_get_attachment_url($thumbnail_id),
        ];
    }
}

$result = [
    'site' => home_url(),
    'target_year' => (int) $target_year,
    'selected_post_count' => count($post_ids),
    'selected_term_count' => count($term_map),
    'selected_attachment_count' => count($attachment_map),
    'post_ids' => $post_ids,
    'slugs_csv' => implode(',', $slugs),
    'sample_urls' => array_values($sample_urls),
    'terms' => array_values($term_map),
    'attachments' => array_values($attachment_map),
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
