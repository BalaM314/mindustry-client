package mindustry.world.blocks.defense.turrets;

import arc.struct.*;
import mindustry.client.navigation.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.world.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class BaseTurret extends Block{
    public float range = 80f;
    public float rotateSpeed = 5;

    public boolean acceptCoolant = true;
    /** Effect displayed when coolant is used. */
    public Effect coolEffect = Fx.fuelburn;
    /** How much reload is lowered by for each unit of liquid of heat capacity. */
    public float coolantMultiplier = 5f;

    public BaseTurret(String name){
        super(name);

        update = true;
        solid = true;
        outlineIcon = true;
        priority = TargetPriority.turret;
        group = BlockGroup.turrets;
        flags = EnumSet.of(BlockFlag.turret);
        updateInUnits = false;
    }

    @Override
    public void init(){
        if(acceptCoolant && !consumes.has(ConsumeType.liquid)){
            hasLiquids = true;
            consumes.add(new ConsumeCoolant(0.2f)).update(false).boost();
        }

        super.init();
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);

        Drawf.dashCircle(x * tilesize + offset, y * tilesize + offset, range, Pal.placing);
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(Stat.shootRange, range / tilesize, StatUnit.blocks);
    }

    public class BaseTurretBuild extends Building implements Ranged{
        public float rotation = 90;
        protected TurretPathfindingEntity turretEnt;

        @Override
        public void add(){ // Client stuff
            super.add();
            turretEnt = new TurretPathfindingEntity(this, range, targetGround(), targetAir(), this::canShoot);
            Navigation.addEnt(turretEnt);
        }

        @Override
        public void remove(){ // Client stuff
            super.remove();
            if(turretEnt != null){
                Navigation.removeEnt(turretEnt);
            }
        }

        @Override
        public float range(){
            return range;
        }

        @Override
        public void drawSelect(){
            Drawf.dashCircle(x, y, range, team.color);
        }

        public boolean canShoot(){ // Client stuff
            return cons.valid();
        }

        public boolean targetGround(){ // Client stuff
            return false;
        }

        public boolean targetAir(){ // Client stuff
            return false;
        }
    }
}
