# MDVMounts 1.1.9

> 1.1.9 endurece `mdv_mount_camel_normal_jump`: el CAMEL etiquetado nunca usa el dash vanilla; SPACE siempre dispara el salto normal configurado. La supresión es barata y sólo se mantiene para conductores que están usando/recién soltaron SPACE.

# MDVMounts 1.1.8

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

La movilidad estable, el almacenamiento por invocador y las habilidades activas se conservan. 1.1.7 añadió salto normal para CAMEL vanilla y trepado tipo araña; 1.1.8 añade protección opcional del jinete contra daño de caída mediante tag.

## Habilidades de montura por tags

La configuración general vive en `config.yml`:

```yaml
control:
  mount-skills:
    enabled: true
    input: SPRINT
    tag-prefix: mdv_mount_skill_
    use-rider-look-direction: true
    rider-look-sync-ticks: 10
    preserve-skill-velocity-ticks: 8
```

`SPRINT` corresponde a la acción de sprint de Minecraft (Ctrl suele ser la tecla predeterminada). Si un jugador cambia su tecla de sprint en el cliente, la habilidad sigue funcionando porque Paper informa la acción lógica, no la tecla física.

Inputs admitidos:

- `SPRINT` (`CTRL` / `CONTROL` también se aceptan)
- `JUMP` (`SPACE`)
- `SNEAK` (`SHIFT`)
- `FORWARD` (`W`)
- `BACKWARD` (`S`)
- `LEFT` (`A`)
- `RIGHT` (`D`)

Para asignar una skill a una montura, se añade un scoreboard tag cuyo sufijo sea exactamente el nombre de la skill MythicMobs:

```yaml
TORO_CARGA:
  # ...
  Skills:
  - addtag{tag=mdv_mount} @self ~onSpawn
  - addtag{tag=mdv_mount_ground} @self ~onSpawn
  - addtag{tag=mdv_mount_skill_TORO_EMBESTIDA} @self ~onSpawn
```

Al pulsar `SPRINT` mientras se conduce ese toro, MDVMounts ejecuta:

```text
TORO_EMBESTIDA
```

La montura es el caster de MythicMobs y el jugador que la conduce se pasa como `@trigger` cuando la versión de MythicMobs ofrece ese overload de la API.

Ejemplo de skill:

```yaml
TORO_EMBESTIDA:
  Skills:
  - sound{s=entity.ravager.roar;v=1;p=1} @self
  - lunge{velocity=1.5;velocityY=0.1} @self
```

Los cooldowns, costes, condiciones y efectos siguen definiéndose en MythicMobs. MDVMounts únicamente detecta la pulsación y dispara la skill.

### Sin spam al mantener la tecla

La habilidad sólo se ejecuta en la transición `soltado -> pulsado`. Mantener Ctrl/Sprint apretado no vuelve a lanzar la skill cada tick. Para volver a activarla hay que soltar y pulsar otra vez.

### Más de un tag

Si una montura tiene más de un tag con el prefijo configurado, cada skill distinta se ejecuta una vez en esa pulsación. Los nombres se ordenan para mantener comportamiento determinista.


## Apuntado 3D desde la cámara del jinete

Con `use-rider-look-direction: true`, al activar una skill MDVMounts copia yaw y pitch del jinete a la montura durante una ventana corta y también pasa ese ángulo como `origin` al cast de MythicMobs. Así, targeters como:

```yaml
@Forward{f=20;lockpitch=false}
```

pueden disparar hacia arriba o hacia abajo según donde mire el jugador montado. El pitch sólo se sincroniza durante `rider-look-sync-ticks`; fuera de esa ventana el controlador conserva el comportamiento normal de rotación horizontal.

Para `@LivingInCone`, MythicMobs requiere activar explícitamente el pitch del cono:

```yaml
@LivingInCone{a=180;r=4;rot=0;usepitch=true}
```

## Dash/lunge mientras la montura ya se mueve

Cuando una skill cambia inmediatamente la velocidad de la montura (por ejemplo `lunge`), MDVMounts detecta ese cambio y deja de sobrescribir la velocidad con WASD durante `preserve-skill-velocity-ticks`. Esto evita que un dash se cancele por estar manteniendo W/A/S/D. Las skills que no cambian la velocidad, como proyectiles o sonidos, no activan esta pausa de movimiento.

## Camello vanilla: SPACE como salto normal

Un `CAMEL` real puede conservar sus dos pasajeros y su conducción vanilla sin
añadir `mdv_mount` ni `mdv_mount_ground`. Basta con:

```yaml
- addtag{tag=mdv_mount_camel_normal_jump} @self ~onSpawn
```

La fuerza del salto vive en:

```yaml
control:
  camel-normal-jump:
    enabled: true
    jump-velocity: 0.55
```

MDVMounts escucha `PlayerInputEvent` incluso sin crear una sesión manual para
ese camello. Solo el primer pasajero conduce. En cada transición de SPACE
desactiva el estado de dash del CAMEL y, al pulsar desde el suelo, conserva X/Z
vanilla y sustituye únicamente Y por el salto configurado.

## Trepado de paredes

Para una montura manual terrestre:

```yaml
- addtag{tag=mdv_mount} @self ~onSpawn
- addtag{tag=mdv_mount_ground} @self ~onSpawn
- addtag{tag=mdv_mount_climber} @self ~onSpawn
```

`STEP_HEIGHT=1.0` sigue resolviendo escalones normales. El trepado solo entra
cuando hay una pared real delante: en suelo exige obstáculo a dos alturas para
no confundir un escalón de un bloque con una pared; una vez trepando, mantiene
Y positiva hasta superar el borde.

```yaml
control:
  wall-climbing:
    enabled: true
    vertical-speed: 0.22
    probe-distance: 0.12
```

No hay raytraces ni búsquedas de entidades. Solo el climber que se está moviendo
hace una o dos comprobaciones `Block#isPassable` en la dirección solicitada.

## Protección de caída del jinete

Añade este tag a cualquier montura que deba proteger a sus pasajeros del daño de caída:

```yaml
- addtag{tag=mdv_mount_rider_fall_protection} @self ~onSpawn
```

Mientras el jugador siga montado sobre esa entidad, MDVMounts cancela únicamente su evento `FALL`. La montura mantiene intacto su propio daño de caída y puede seguir usando `GENERIC_FALL_DAMAGE_MULTIPLIER` para reducirlo o aumentarlo.

El sistema también cubre al segundo pasajero de un CAMEL vanilla. Es completamente event-driven: no añade timers, tareas por tick, raytraces ni búsquedas de entidades.

La clave del tag puede cambiarse en `config.yml`:

```yaml
tags:
  rider-fall-protection: mdv_mount_rider_fall_protection
```

## Rendimiento

No se añade ningún timer global nuevo ni scan de entidades. La protección de caída solo se evalúa cuando ya existe un evento FALL de jugador. El salto del camello usa `PlayerInputEvent`. El trepado reutiliza el tick de movimiento que ya existe y sólo, para una montura marcada como `mdv_mount_climber` que está intentando moverse, consulta 1-2 bloques inmediatamente delante de su hitbox.

La integración con MythicMobs usa un bridge ligero: descubre y cachea `BukkitAPIHelper.castSkill(...)` una vez al iniciar. No realiza búsquedas de métodos en cada pulsación.

## Almacenamiento por invocador

Desde 1.1.6, la apertura del almacenamiento y el click de invocación se configuran por separado. `control.storage-invocation-interaction: AUTO` es el modo recomendado: permite usar `~onUse`, `~onSwing` o variantes con Shift sin perder el enlace físico entre silbato y montura.


Se mantiene el comportamiento de 1.1.3:

- cada ItemStack físico conserva su propio `minecraft:container`;
- single-viewer lock;
- cierre al morir/despawnear/remover la montura;
- tooltip del contenedor oculto;
- interacción del almacenamiento configurable;
- `storage.yml` separado;
- sin SQLite.

## Autoactualización

`config.yml` y `storage.yml` se autoactualizan al iniciar y con `/mdvmounts reload`. Las claves nuevas incluidas en el JAR se agregan sin reemplazar valores existentes.

Por eso una instalación 1.1.3 recibirá automáticamente:

```yaml
control:
  mount-skills:
    enabled: true
    input: SPRINT
    tag-prefix: mdv_mount_skill_
```

## Compilar

Java 21 + Maven:

```bash
mvn clean package
```

Resultado:

```text
target/MDVMounts-1.1.8.jar
```
