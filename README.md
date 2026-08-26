# MDVMounts 1.1.1

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

## 1.1.1 - Almacenamiento por invocador endurecido

La movilidad de 1.0.6 no se modifica. Esta versión ajusta únicamente las alforjas/almacenamiento.

### Controles del invocador

- **Click izquierdo** con un invocador configurado: abre su almacenamiento si la montura exacta ligada a ese ItemStack está invocada y dentro de `interaction-distance`.
- **Click derecho**: MDVMounts no abre almacenamiento. Crucible/MythicMobs conserva la invocación normal y MDVMounts enlaza después el mob recién creado con ese ItemStack físico.

### Modelo de almacenamiento

- Cada ItemStack físico recibe un UUID propio cuando consigue invocar una montura compatible.
- Los objetos se guardan dentro del mismo ItemStack con `DataComponentTypes.CONTAINER` (`minecraft:container`).
- No usa SQLite.
- Dos silbatos visualmente idénticos tienen inventarios distintos.
- Si otro jugador roba o recoge el mismo silbato, hereda también su contenido.
- Sólo se abre si está cerca la montura enlazada a ESE silbato concreto.
- Un mismo almacenamiento tiene **single-viewer lock**: sólo una persona puede verlo a la vez, incluso si el item cambia de manos por una mecánica externa.
- Si la montura ligada muere, despawnea, es removida por MythicMobs o sale del mundo, el menú abierto se guarda y se cierra inmediatamente.

### Configuración separada

La movilidad permanece en `config.yml`.

Los inventarios viven en:

```text
plugins/MDVMounts/storage.yml
```

Ejemplo:

```yaml
profiles:
  caravan:
    enabled: true
    slots: 27
    title: '&6Alforjas del Caballo'
    interaction-distance: 3.0

    invoker:
      material: SADDLE
      display-name: '&e&lSilbato de Caravana'

    mount:
      required-tags:
        - mdv_storage_caravana
```

Y el mob:

```yaml
Skills:
- addtag{tag=mdv_storage_caravana} @self ~onSpawn
```

`slots` admite de 1 a 54 espacios reales.

### Autoactualización y migración

`config.yml` y `storage.yml` se autoactualizan al iniciar y con `/mdvmounts reload`: las claves nuevas del JAR se agregan sin reemplazar valores existentes.

Si se actualiza desde 1.1.0 y `config.yml` todavía contiene la sección `storage:`, MDVMounts la migra automáticamente a `storage.yml` y elimina la sección antigua del archivo principal. También migra mensajes `storage-*` antiguos.

## Movilidad cerrada (1.0.6)

- Tags: `mdv_mount` + uno de `mdv_mount_ground`, `mdv_mount_flying`, `mdv_mount_aquatic`, `mdv_mount_lava`, `mdv_mount_jumper`.
- Respuesta inmediata con `PlayerInputEvent`.
- `MOVEMENT_SPEED` y `JUMP_STRENGTH` cacheados.
- Ground usa `STEP_HEIGHT` nativo para subir desniveles sin raytraces.
- Flying mantiene altura al quedar sin input.
- Sin scans globales de entidades para movilidad.

## Rendimiento del almacenamiento

No añade timers ni scans globales. Las búsquedas de montura ocurren sólo al usar un invocador configurado y durante los pocos ticks de enlace posteriores a la invocación. El cierre por muerte/despawn es por eventos, no por polling.

## Compilar

Java 21 + Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.1.2.jar
```

## 1.1.2 - aislamiento de almacenamiento por invocador

- El contenido sigue viviendo dentro del `ItemStack` mediante `minecraft:container`.
- Cada invocación exitosa genera un UUID de enlace nuevo entre ese silbato físico y su montura.
- Al guardar un GUI, MDVMounts escribe primero en el slot exacto desde el que se abrió, evitando contaminar otro invocador con un UUID duplicado.
- No se añadieron timers ni cambios al controlador de movimiento.
