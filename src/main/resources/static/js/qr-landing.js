/**
 * Landing page QR code — renders a QR pointing to the GitHub repo.
 * Uses qrcode-generator (vendored) to build an inline SVG.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var container = document.getElementById('qrCode');
        if (!container || typeof qrcode !== 'function') return;

        var url = container.getAttribute('data-url');
        if (!url) return;

        var qr = qrcode(0, 'M');
        qr.addData(url);
        qr.make();
        container.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 1, scalable: true });
    });
})();
