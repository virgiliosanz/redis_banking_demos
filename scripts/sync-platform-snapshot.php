<?php

$stylesheet = (string) get_option('stylesheet', '');
$template = (string) get_option('template', '');
$active_plugins = get_option('active_plugins', []);
if (!is_array($active_plugins)) {
    $active_plugins = [];
}
sort($active_plugins);

$theme_mods = get_option('theme_mods_' . $stylesheet, []);
if (!is_array($theme_mods)) {
    $theme_mods = [];
}
ksort($theme_mods);

$sidebars_widgets = get_option('sidebars_widgets', []);
if (!is_array($sidebars_widgets)) {
    $sidebars_widgets = [];
}
ksort($sidebars_widgets);

$nav_menu_locations = [];
$locations = get_nav_menu_locations();
if (is_array($locations)) {
    ksort($locations);
    $nav_menu_locations = $locations;
}

$result = [
    'site' => home_url(),
    'stylesheet' => $stylesheet,
    'template' => $template,
    'active_plugins' => array_values($active_plugins),
    'allowlist_option_names' => [
        'sidebars_widgets',
        'nav_menu_locations',
        'theme_mods_' . $stylesheet,
    ],
    'theme_mods_hash' => hash('sha256', wp_json_encode($theme_mods)),
    'sidebars_widgets_hash' => hash('sha256', wp_json_encode($sidebars_widgets)),
    'nav_menu_locations_hash' => hash('sha256', wp_json_encode($nav_menu_locations)),
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
