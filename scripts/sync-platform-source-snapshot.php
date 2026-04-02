<?php

$stylesheet = (string) get_option('stylesheet', '');
$template = (string) get_option('template', '');

$active_plugins = get_option('active_plugins', []);
if (!is_array($active_plugins)) {
    $active_plugins = [];
}
sort($active_plugins);

$sidebars_widgets = get_option('sidebars_widgets', []);
if (!is_array($sidebars_widgets)) {
    $sidebars_widgets = [];
}
ksort($sidebars_widgets);

$nav_menu_locations = get_option('nav_menu_locations', []);
if (!is_array($nav_menu_locations)) {
    $nav_menu_locations = [];
}
ksort($nav_menu_locations);

$theme_mods_option_name = 'theme_mods_' . $stylesheet;
$theme_mods = get_option($theme_mods_option_name, []);
if (!is_array($theme_mods)) {
    $theme_mods = [];
}
ksort($theme_mods);

$result = [
    'site' => home_url(),
    'stylesheet' => $stylesheet,
    'template' => $template,
    'active_plugins' => array_values($active_plugins),
    'allowlist_options' => [
        'sidebars_widgets' => $sidebars_widgets,
        'nav_menu_locations' => $nav_menu_locations,
        $theme_mods_option_name => $theme_mods,
    ],
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
