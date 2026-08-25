# MDVMounts 1.1.0

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

## 1.1.0 - Alforjas ligadas al invocador

Se mantiene intacta la movilidad de 1.0.6 y se agrega almacenamiento opcional para invocadores concretos.

### Modelo de almacenamiento

- Cada ItemStack físico configurado como invocador recibe un UUID propio al primer uso.
- Los objetos se guardan dentro de ese mismo ItemStack usando `DataComponentTypes.CONTAINER` (`minecraft:container`).
- No usa SQLite ni un archivo por montura.
- Dos Silbatos de Caravana idénticos visualmente tienen inventarios independientes.
- Si un jugador muere y otro recoge el silbato, el segundo se lleva también el contenido almacenado.
- El almacenamiento sólo se puede abrir si está cerca la montura que fue enlazada a ESE ItemStack físico.
- Sólo tienen almacenamiento los perfiles definidos en `storage.profiles`.

### Ejemplo: Silbato de Caravana

En `config.yml` viene este perfil de ejemplo:

```yaml
storage:
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

Al `CABALLO_CARAVANA` hay que añadirle el tag específico del perfil:

```yaml
Skills:
- addtag{tag=mdv_storage_caravana} @self ~onSpawn
```

El invocador de Crucible puede seguir exactamente con la misma skill que ya usaba:

```yaml
INVOCADOR_CABALLO_CARAVANA:
  Id: SADDLE
  Display: '&e&lSilbato de Caravana'
  Skills:
  - skill{
      s=MDV_MOUNT_INVOCAR;
      mob=CABALLO_CARAVANA;
      nombre="&6Caballo de Caravana"
    } @self ~onUse
```

Flujo:

```text
Silbato A invoca -> CABALLO_CARAVANA queda enlazado al UUID de Silbato A
Silbato A + caballo a <=3 bloques -> click derecho abre inventario A
Silbato B + caballo de A cerca -> NO abre inventario A
Sin montura correspondiente cerca -> MDVMounts no cancela el uso y Crucible invoca normalmente
```

`slots` admite de 1 a 54 espacios reales. Si se configura, por ejemplo, `slots: 20`, la GUI usa 27 espacios visuales pero los 7 sobrantes quedan bloqueados.

Para evitar contenedores recursivos, por defecto un invocador con almacenamiento no se puede guardar dentro de otro:

```yaml
storage:
  prevent-nested-invokers: true
```

### Rendimiento del almacenamiento

No añade timers ni scans globales. La búsqueda de la montura sólo ocurre al usar un invocador configurado y durante unos pocos ticks después del click para enlazar el mob recién invocado. El contenido sólo se serializa al cerrar la GUI, morir, salir o apagar el plugin.

## 1.0.6 - Movilidad cerrada

- Las monturas `mdv_mount_flying` mantienen la altura exacta al soltar todos los controles.
- El hover no recalcula dirección ni atributos: sólo corrige `velocityY` si una entidad voladora intenta subir o bajar por sí sola.
- Se mantiene el frenado único al soltar input, evitando `setVelocity(0,0,0)` redundante cada tick.
- No hay lógica de detección de disguises.

## Filosofía

- MythicMobs crea y configura el mob.
- MMOItems/Crucible crea el invocador.
- MythicMobs define vida, `MovementSpeed`, `JumpStrength`, skills, modelos y demás stats.
- MDVMounts controla movilidad y, opcionalmente, el almacenamiento ligado al ItemStack.
- No usa NMS ni ProtocolLib.
- No depende de LibsDisguises.

## Tags de movilidad

Todo mob controlable debe tener:

```text
mdv_mount
```

y un controlador principal:

```text
mdv_mount_ground
mdv_mount_flying
mdv_mount_aquatic
mdv_mount_lava
mdv_mount_jumper
```

Prioridad si hay varios: `FLYING > AQUATIC > LAVA > JUMPER > GROUND`.

## Controles

### Ground

- W/A/S/D: movimiento inmediato según `movement-percentages.ground`.
- SPACE: salto usando `JUMP_STRENGTH`.
- SHIFT: desmontaje normal de Minecraft.
- `STEP_HEIGHT`: permite subir desniveles configurados sin escaneos custom.

### Flying / Aquatic / Lava

Conservan el comportamiento de 1.0.6. Sus porcentajes horizontales se configuran independientemente en `config.yml`.

### Jumper

Usa el perfil terrestre y salta automáticamente mientras haya input horizontal.

## Rendimiento

- Un único loop sobre sesiones activas; no hay scans globales de entidades.
- `MOVEMENT_SPEED` y `JUMP_STRENGTH` se refrescan cada `performance.attribute-refresh-ticks`.
- `STEP_HEIGHT` se aplica al montar y se restaura al desmontar.
- El yaw cachea seno/coseno mientras no cambie.
- `PlayerInputEvent` sólo aplica respuesta extra cuando cambia el estado de teclas.
- Las alforjas no añaden ningún loop periódico.

## Compilar

Java 21 + Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.1.0.jar
```

El workflow incluido verifica compilación contra Paper 1.21.6 y 1.21.11.
