<?php

$snapshot_file = getenv('ROLLOVER_SNAPSHOT_FILE');
if ($snapshot_file === false || $snapshot_file === '' || !file_exists($snapshot_file)) {
    fwrite(STDERR, "Missing or invalid ROLLOVER_SNAPSHOT_FILE\n");
    exit(1);
}

$snapshot = json_decode((string) file_get_contents($snapshot_file), true);
if (!is_array($snapshot) || !isset($snapshot['posts']) || !is_array($snapshot['posts'])) {
    fwrite(STDERR, "Invalid snapshot file\n");
    exit(1);
}

$created_terms = [];
foreach (($snapshot['terms'] ?? []) as $term_data) {
    if (!is_array($term_data) || empty($term_data['taxonomy']) || empty($term_data['slug'])) {
        continue;
    }

    $taxonomy = (string) $term_data['taxonomy'];
    $slug = (string) $term_data['slug'];
    $name = (string) ($term_data['name'] ?? $slug);

    if (term_exists($slug, $taxonomy)) {
        continue;
    }

    wp_insert_term($name, $taxonomy, ['slug' => $slug]);
    $created_terms[] = $taxonomy . ':' . $slug;
}

$attachment_map = [];
foreach (($snapshot['attachments'] ?? []) as $attachment_data) {
    if (!is_array($attachment_data) || empty($attachment_data['post_name'])) {
        continue;
    }

    $existing = get_page_by_path((string) $attachment_data['post_name'], OBJECT, 'attachment');
    if ($existing instanceof WP_Post) {
        $attachment_map[(int) $attachment_data['source_post_id']] = (int) $existing->ID;
        continue;
    }

    $attachment_id = wp_insert_post([
        'post_type' => 'attachment',
        'post_status' => 'inherit',
        'post_title' => (string) ($attachment_data['post_title'] ?? ''),
        'post_name' => (string) $attachment_data['post_name'],
        'post_date' => (string) ($attachment_data['post_date'] ?? current_time('mysql')),
        'post_excerpt' => (string) ($attachment_data['post_excerpt'] ?? ''),
        'post_content' => (string) ($attachment_data['post_content'] ?? ''),
        'post_mime_type' => (string) ($attachment_data['post_mime_type'] ?? ''),
        'guid' => (string) ($attachment_data['guid'] ?? ''),
    ], true);

    if (is_wp_error($attachment_id)) {
        fwrite(STDERR, "Could not import attachment " . $attachment_data['post_name'] . "\n");
        exit(1);
    }

    foreach (($attachment_data['meta'] ?? []) as $meta_key => $values) {
        delete_post_meta($attachment_id, $meta_key);
        if (!is_array($values)) {
            continue;
        }
        foreach ($values as $value) {
            add_post_meta($attachment_id, $meta_key, $value);
        }
    }

    $attachment_map[(int) $attachment_data['source_post_id']] = (int) $attachment_id;
}

$created_posts = [];
$updated_posts = [];

foreach ($snapshot['posts'] as $post_data) {
    if (!is_array($post_data) || empty($post_data['post_name'])) {
        continue;
    }

    $existing = get_page_by_path((string) $post_data['post_name'], OBJECT, 'post');
    $author_login = (string) ($post_data['author_login'] ?? '');
    $author = $author_login !== '' ? get_user_by('login', $author_login) : false;
    $author_id = $author instanceof WP_User ? (int) $author->ID : 1;

    $payload = [
        'post_type' => 'post',
        'post_status' => (string) ($post_data['post_status'] ?? 'publish'),
        'post_title' => (string) ($post_data['post_title'] ?? ''),
        'post_name' => (string) $post_data['post_name'],
        'post_date' => (string) ($post_data['post_date'] ?? current_time('mysql')),
        'post_excerpt' => (string) ($post_data['post_excerpt'] ?? ''),
        'post_content' => (string) ($post_data['post_content'] ?? ''),
        'post_author' => $author_id,
    ];

    if ($existing instanceof WP_Post) {
        $payload['ID'] = (int) $existing->ID;
        $post_id = wp_update_post($payload, true);
        $updated_posts[] = $post_data['post_name'];
    } else {
        $post_id = wp_insert_post($payload, true);
        $created_posts[] = $post_data['post_name'];
    }

    if (is_wp_error($post_id)) {
        fwrite(STDERR, "Could not import post " . $post_data['post_name'] . "\n");
        exit(1);
    }

    $terms_by_taxonomy = [];
    foreach (($post_data['terms'] ?? []) as $term_ref) {
        if (!is_array($term_ref) || empty($term_ref['taxonomy']) || empty($term_ref['slug'])) {
            continue;
        }
        $terms_by_taxonomy[(string) $term_ref['taxonomy']][] = (string) $term_ref['slug'];
    }
    foreach ($terms_by_taxonomy as $taxonomy => $slugs) {
        wp_set_object_terms($post_id, $slugs, $taxonomy, false);
    }

    foreach (($post_data['meta'] ?? []) as $meta_key => $values) {
        delete_post_meta($post_id, $meta_key);
        if (!is_array($values)) {
            continue;
        }
        foreach ($values as $value) {
            if ($meta_key === '_thumbnail_id') {
                $source_attachment_id = (int) $value;
                $mapped_attachment_id = $attachment_map[$source_attachment_id] ?? 0;
                if ($mapped_attachment_id > 0) {
                    add_post_meta($post_id, $meta_key, $mapped_attachment_id);
                }
                continue;
            }
            add_post_meta($post_id, $meta_key, $value);
        }
    }
}

$result = [
    'site' => home_url(),
    'created_terms' => $created_terms,
    'created_posts' => $created_posts,
    'updated_posts' => $updated_posts,
    'attachment_map_size' => count($attachment_map),
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
