# MDVMounts 1.0.6

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

## 1.0.6

- Las monturas `mdv_mount_flying` mantienen la altura exacta al soltar todos los controles.
- El hover no recalcula dirección ni atributos: sólo corrige `velocityY` si una entidad voladora intenta subir o bajar por sí sola.
- Se mantiene el frenado único al soltar input, evitando `setVelocity(0,0,0)` redundante cada tick.
- Eliminado el archivo residual `DisguiseSupport.java`; el proyecto no contiene lógica de detección de disguises.

## 1.0.5

- Eliminada por completo la lógica de detección de Disguise/LibsDisguises.
- Eliminado `vertical-dismount` y toda la lógica de múltiples pulsaciones de SHIFT.
- Todas las monturas `mdv_mount_ground` usan el mismo controlador terrestre inmediato tipo caballo.
- Se mantienen los perfiles configurables de velocidad `forward`, `backward` y `lateral` para cada controlador.
- Se mantiene `STEP_HEIGHT` para subir desniveles sin raytraces ni búsquedas de bloques por tick.
- Se mantiene `PlayerInputEvent` para respuesta inmediata al cambiar W/A/S/D/JUMP.
- Se mantienen cacheados `MOVEMENT_SPEED` y `JUMP_STRENGTH`.

## Filosofía

MDVMounts sólo detecta entidades por scoreboard tags y convierte el input del jinete en movimiento.

- MythicMobs crea y configura el mob.
- MMOItems/Crucible puede crear el invocador.
- MythicMobs define vida, `MovementSpeed`, `JumpStrength`, skills, modelos y demás stats.
- MDVMounts no busca entidades cercanas ni escanea bloques para mover la montura.
- No usa NMS ni ProtocolLib.
- No depende de LibsDisguises.

## Tags

Todo mob debe tener por defecto:

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

Conservan el comportamiento de control de la 1.0.4. Sus porcentajes horizontales se configuran independientemente en `config.yml`.

### Jumper

Usa el perfil terrestre y salta automáticamente mientras haya input horizontal.

## Rendimiento

- Un único loop sobre sesiones activas; no hay scans globales de entidades.
- `MOVEMENT_SPEED` y `JUMP_STRENGTH` se refrescan cada `performance.attribute-refresh-ticks` (10 por defecto).
- `STEP_HEIGHT` se aplica al montar y se restaura al desmontar.
- El yaw cachea seno/coseno mientras no cambie, evitando trigonometría redundante.
- `PlayerInputEvent` sólo aplica respuesta extra cuando cambia el estado de teclas.

## Compilar

Java 21 + Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.0.6.jar
```

El workflow incluido también verifica compilación contra Paper 1.21.6 y 1.21.11.
