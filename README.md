# MDVMounts 1.1.3

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

La movilidad estable de 1.0.6 no se modifica. Esta versión ajusta únicamente el almacenamiento por invocador.

## Almacenamiento por invocador

- Cada ItemStack físico mantiene su propio contenido mediante `DataComponentTypes.CONTAINER` (`minecraft:container`).
- Dos silbatos visualmente idénticos conservan inventarios separados.
- Cada invocación exitosa renueva el UUID de enlace entre ESE silbato físico y su montura, evitando identidades duplicadas.
- Si otro jugador roba o recoge el mismo silbato, también hereda su contenido.
- Sólo se abre si la montura exacta ligada a ese silbato está invocada y dentro de `interaction-distance`.
- Un almacenamiento tiene single-viewer lock: una sola persona puede verlo a la vez.
- Si la montura muere, despawnea, es removida o sale del mundo, el menú se guarda y se cierra.
- No usa SQLite.

## 1.1.3 - controles configurables

La acción para abrir las alforjas se elige en `config.yml`:

```yaml
control:
  storage-open-interaction: LEFT_CLICK
```

Valores admitidos:

- `LEFT_CLICK`
- `RIGHT_CLICK`
- `SHIFT_LEFT_CLICK`
- `SHIFT_RIGHT_CLICK`

`LEFT_CLICK` mantiene el comportamiento de 1.1.2. Un click derecho que no abra realmente el almacenamiento sigue disponible para Crucible/MythicMobs, de modo que la invocación normal no se rompe.

## 1.1.3 - tooltip limpio

El contenido continúa guardándose en `minecraft:container`, pero MDVMounts añade `CONTAINER` a los componentes ocultos del `TOOLTIP_DISPLAY`. Así Minecraft deja de mostrar el listado estilo shulker sobre el lore del silbato, sin ocultar el nombre ni el lore personalizado.

Los silbatos ya existentes se corrigen al entrar el jugador o al ejecutar `/mdvmounts reload`. Los nuevos quedan corregidos automáticamente al enlazarse/guardar contenido.

## Configuración separada

La movilidad y los controles generales permanecen en `config.yml`. Los perfiles de inventario viven en:

```text
plugins/MDVMounts/storage.yml
```

Ejemplo:

```yaml
profiles:
  toro_carga:
    enabled: true
    slots: 20
    title: '&6Carga del Toro'
    interaction-distance: 3.0

    invoker:
      material: SADDLE
      display-name: '&e&lSilbato de Toro de Carga'

    mount:
      required-tags:
        - mdv_storage_toro_carga
```

`slots` admite de 1 a 54 espacios reales.

## Autoactualización

`config.yml` y `storage.yml` se autoactualizan al iniciar y con `/mdvmounts reload`: las claves nuevas del JAR se agregan sin reemplazar valores existentes.

Las instalaciones antiguas que todavía tengan `storage:` dentro de `config.yml` se migran automáticamente a `storage.yml`.

## Rendimiento

No se añaden timers ni scans globales para este cambio. La migración visual del tooltip sólo hace un recorrido del inventario del jugador al entrar o al recargar la configuración. Las búsquedas de montura siguen ocurriendo sólo al usar un invocador configurado y durante la pequeña ventana de enlace posterior a una invocación.

## Compilar

Java 21 + Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.1.3.jar
```
