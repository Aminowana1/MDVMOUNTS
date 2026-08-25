# MDVMounts 1.0.4


## 1.0.4

- Todas las monturas manuales `mdv_mount_ground` usan un perfil de conducción tipo caballo: W/A/S/D sin interpolación artificial, salto con `JUMP_STRENGTH` y giro con el jinete.
- Añade auto-step económico mediante el atributo nativo `STEP_HEIGHT` (1.0 por defecto). No hay raytraces ni búsquedas de bloques por tick; Minecraft resuelve la colisión y el escalón.
- Añade respuesta inmediata a cambios de input mediante `PlayerInputEvent`: al pulsar o soltar W/A/S/D/JUMP se aplica el cambio en ese mismo evento, mientras el loop normal mantiene el movimiento.
- Los caballos reales sin Disguise continúan usando steering vanilla auténtico y no entran en el controlador manual.
- Los caballos con Disguise y cualquier otro mob GROUND usan la emulación terrestre tipo caballo.
- `STEP_HEIGHT` original se restaura al desmontar.

## 1.0.3

- Corrige `HORSE`/`DONKEY`/`MULE` con `Disguise`: LibsDisguises hace que el cliente pierda el steering vanilla cuando el caballo se muestra como otro tipo de entidad. MDVMounts detecta el Disguise una sola vez al montar y usa automáticamente el controlador terrestre manual de respuesta inmediata.
- Los caballos reales **sin** Disguise siguen usando control vanilla auténtico y no pasan por el cálculo de movimiento por tick.
- Los caballos disfrazados usan `control.movement-percentages.ground`, así que `forward`, `backward` y `lateral` siguen siendo configurables igual que en las demás monturas.
- La detección de LibsDisguises usa un puente opcional por reflexión: no añade dependencia dura ni coste por tick.

## 1.0.2

- Se añadieron perfiles de velocidad horizontal configurables por controlador.
- Se mejoró la recepción de interacciones sobre entidades disfrazadas.


Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

## Filosofía

MDVMounts sólo se encarga de **detectar una entidad marcada y convertir el input del jinete en movimiento**.

- MythicMobs crea el mob.
- MMOItems/Crucible puede crear el invocador.
- MythicMobs define vida, `MovementSpeed`, modelos, sonidos, skills y demás stats.
- MDVMounts **no define ni multiplica velocidades por tipo de mob**.
- Los caballos reales (`HORSE`, `DONKEY`, `MULE`, etc.) con tag `mdv_mount_ground` y **sin Disguise** usan el control montado nativo de Minecraft: no se calcula su velocidad por tick y el WASD se siente exactamente vanilla.
- Si un caballo real está disfrazado como otra entidad, el steering vanilla deja de estar disponible desde el cliente; MDVMounts cambia automáticamente a su controlador GROUND inmediato para mantener el Disguise y el control WASD.
- Las monturas manuales (vuelo, agua, lava, jumper y terrestres no-caballo) usan respuesta WASD inmediata, sin interpolación de aceleración.
- `MOVEMENT_SPEED` y `JUMP_STRENGTH` se cachean y se refrescan cada pocos ticks (10 por defecto), así los cambios de MythicMobs se siguen reflejando sin consultar atributos cada tick.
- No hay dependencia de MythicMobs API: se usan scoreboard tags, lo que reduce muchísimo los problemas entre versiones.
- No usa NMS ni ProtocolLib.

El código es una implementación propia. No incluye ni depende de MobSteer.

## Tags

Por defecto todo mob debe tener:

```text
mdv_mount
```

y exactamente un comportamiento principal:

```text
mdv_mount_ground
mdv_mount_flying
mdv_mount_aquatic
mdv_mount_lava
mdv_mount_jumper
```

Si una entidad tiene varios tags de modo, la prioridad es:

`FLYING > AQUATIC > LAVA > JUMPER > GROUND`.

## Ejemplos MythicMobs

### Terrestre

```yaml
CABALLO_MDV:
  Type: HORSE
  Health: 40
  Options:
    MovementSpeed: 0.30
  Skills:
    - addtag{t=mdv_mount} @self ~onSpawn
    - addtag{t=mdv_mount_ground} @self ~onSpawn
```

Controles: WASD, SPACE para saltar, SHIFT para desmontar.

### Voladora

```yaml
GRIFO_MDV:
  Type: PHANTOM
  Health: 80
  Options:
    MovementSpeed: 0.36
  Skills:
    - addtag{t=mdv_mount} @self ~onSpawn
    - addtag{t=mdv_mount_flying} @self ~onSpawn
```

Controles: WASD, SPACE sube, SHIFT baja. Tres pulsaciones de SHIFT dentro de 900 ms desmontan por defecto.

### Acuática

```yaml
TIBURON_MDV:
  Type: DOLPHIN
  Health: 60
  Options:
    MovementSpeed: 0.34
  Skills:
    - addtag{t=mdv_mount} @self ~onSpawn
    - addtag{t=mdv_mount_aquatic} @self ~onSpawn
```

Dentro del agua usa movimiento libre. Fuera del agua vuelve al controlador terrestre para no quedar flotando.

### Lava

```yaml
SALAMANDRA_MDV:
  Type: STRIDER
  Health: 70
  Options:
    MovementSpeed: 0.31
  Skills:
    - addtag{t=mdv_mount} @self ~onSpawn
    - addtag{t=mdv_mount_lava} @self ~onSpawn
```

Dentro de lava funciona como controlador tridimensional. Fuera de lava usa movimiento terrestre.

### Slime / saltador

```yaml
SLIME_MONTURA:
  Type: SLIME
  Health: 50
  Options:
    MovementSpeed: 0.27
  Skills:
    - addtag{t=mdv_mount} @self ~onSpawn
    - addtag{t=mdv_mount_jumper} @self ~onSpawn
```

Mientras haya input horizontal, salta automáticamente al tocar el suelo. `JUMP_STRENGTH` se usa si la entidad dispone del atributo; de lo contrario se usa el salto vanilla genérico.

## IA y skills de MythicMobs

Con `control.pause-vanilla-ai-while-ridden: true`, MDVMounts hace dos cosas mientras hay jinete:

1. detiene el pathfinder vanilla;
2. usa `Mob#setAware(false)` para que la IA no intente caminar/atacar en dirección contraria al WASD.

Al desmontar, restaura el valor anterior de awareness y gravedad.

MDVMounts **no borra skills, metadata ni configuración de MythicMobs**. Las skills gestionadas por MythicMobs siguen registradas. Naturalmente, una acción que dependa de que la IA vanilla persiga o ataque por sí sola no actuará mientras la criatura está bajo control manual.

## Compilar localmente

Requiere Java 21 y Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.0.4.jar
```

También se puede verificar contra otra revisión de Paper:

```bash
mvn -Dpaper.version=1.21.11-R0.1-SNAPSHOT clean verify
```

## GitHub Actions

`.github/workflows/build.yml`:

- prueba compilación contra Paper 1.21.6;
- prueba compilación contra Paper 1.21.11;
- si ambas pasan, genera un único JAR compilado contra la API mínima 1.21.6;
- sube el JAR como artifact de GitHub Actions.

Para futuras versiones 1.21.x basta con agregar su versión a la matriz de `build.yml`. Al no usar NMS, no hay carpetas específicas por versión ni JARs separados.

## Nota de compatibilidad futura

Ningún plugin puede garantizar compatibilidad con versiones futuras que todavía no existan. Esta arquitectura la maximiza porque usa únicamente API de Paper y scoreboard tags. Si Paper cambia una API en una versión futura, el job de compatibilidad de GitHub fallará y mostrará exactamente qué hay que adaptar.
