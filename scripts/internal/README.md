# Helpers internos

Este arbol contiene helpers consumidos por la capa Python y por `wp-cli eval-file`.

Reglas:
- no se documentan como entrypoints de operador
- sus rutas pueden cambiar en refactors internos siempre que se actualicen las llamadas desde `ops/` y smokes
- si una pieza de este arbol pasa a ser interfaz humana estable, debe volver a `scripts/` raiz y quedar documentada en `scripts/README.md`
