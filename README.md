# MDVMounts 1.1.4

Controlador ligero de monturas para MDVCRAFT sobre Paper/Purpur 1.21.6+.

La movilidad estable y el almacenamiento por invocador de 1.1.3 se conservan. Esta versión añade habilidades activables desde el input del jinete mediante scoreboard tags.

## Habilidades de montura por tags

La configuración general vive en `config.yml`:

```yaml
control:
  mount-skills:
    enabled: true
    input: SPRINT
    tag-prefix: mdv_mount_skill_
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

## Rendimiento

No se añade ningún timer, búsqueda global ni scan periódico. MDVMounts ya recibe `PlayerInputEvent`; el nuevo módulo sólo revisa los scoreboard tags de la montura cuando el input configurado cambia de suelto a pulsado.

La integración con MythicMobs usa un bridge ligero: descubre y cachea `BukkitAPIHelper.castSkill(...)` una vez al iniciar. No realiza búsquedas de métodos en cada pulsación.

## Almacenamiento por invocador

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
target/MDVMounts-1.1.4.jar
```
