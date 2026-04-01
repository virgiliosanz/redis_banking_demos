<?php

declare(strict_types=1);

if (!defined('ABSPATH')) {
    exit;
}

/**
 * Inject a minimal visible search bar into the public header so the POC can
 * validate unified search without relying on raw query-string URLs.
 */
add_filter(
    'render_block',
    static function (string $blockContent, array $block): string {
        if (is_admin()) {
            return $blockContent;
        }

        if (($block['blockName'] ?? '') !== 'core/template-part') {
            return $blockContent;
        }

        $attrs = $block['attrs'] ?? [];
        $area = $attrs['area'] ?? '';
        $slug = $attrs['slug'] ?? '';

        if ($area !== 'header' && $slug !== 'header') {
            return $blockContent;
        }

        static $rendered = false;
        if ($rendered) {
            return $blockContent;
        }
        $rendered = true;

        $currentSearch = get_search_query();
        $searchMarkup = '<section class="n9-search-lab" aria-label="' . esc_attr__('Busqueda del laboratorio', 'nuevecuatrouno') . '">
                <style>
                    .n9-search-lab{border-top:1px solid #d7d2c8;border-bottom:1px solid #d7d2c8;background:#f7f1e8}
                    .n9-search-lab__inner{max-width:1100px;margin:0 auto;padding:16px 24px;display:flex;gap:16px;align-items:center;justify-content:space-between;flex-wrap:wrap}
                    .n9-search-lab__copy{display:flex;flex-direction:column;gap:2px}
                    .n9-search-lab__eyebrow{font:700 11px/1.1 monospace;letter-spacing:.08em;text-transform:uppercase;color:#8a4b08}
                    .n9-search-lab__title{font:600 15px/1.3 sans-serif;color:#1f1a14}
                    .n9-search-lab__form{display:flex;gap:8px;align-items:center;flex:1 1 340px;max-width:520px}
                    .n9-search-lab__input{flex:1 1 auto;padding:12px 14px;border:1px solid #b9aa95;border-radius:999px;background:#fff;color:#1f1a14}
                    .n9-search-lab__button{padding:12px 18px;border:0;border-radius:999px;background:#111;color:#fff;font:600 14px/1 sans-serif;cursor:pointer}
                    @media (max-width: 720px){.n9-search-lab__inner{padding:14px 16px}.n9-search-lab__form{max-width:none;width:100%}}
                </style>
                <div class="n9-search-lab__inner">
                    <div class="n9-search-lab__copy">
                        <span class="n9-search-lab__eyebrow">Busqueda unificada</span>
                        <span class="n9-search-lab__title">Busca en live y archive desde la misma interfaz.</span>
                    </div>
                    <form class="n9-search-lab__form" action="' . esc_url(home_url('/')) . '" method="get">
                        <label class="screen-reader-text" for="n9-search-lab-input">' . esc_html__('Buscar contenido', 'nuevecuatrouno') . '</label>
                        <input id="n9-search-lab-input" class="n9-search-lab__input" type="search" name="s" value="' . esc_attr($currentSearch) . '" placeholder="' . esc_attr__('Busca en live y archive', 'nuevecuatrouno') . '" />
                        <button class="n9-search-lab__button" type="submit">Buscar</button>
                    </form>
                </div>
            </section>';

        return $blockContent . $searchMarkup;
    },
    10,
    2
);
