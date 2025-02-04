package mindustry.input;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.input.GestureDetector.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.formations.patterns.*;
import mindustry.ai.types.*;
import mindustry.annotations.Annotations.*;
import mindustry.client.*;
import mindustry.client.antigrief.*;
import mindustry.client.navigation.*;
import mindustry.client.navigation.waypoints.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.Placement.*;
import mindustry.net.Administration.*;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.ui.fragments.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.PowerNode.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.units.*;
import mindustry.world.meta.*;

import java.util.*;

import static arc.Core.*;
import static mindustry.Vars.*;
import static mindustry.client.ClientVars.*;

public abstract class InputHandler implements InputProcessor, GestureListener{
    /** Used for dropping items. */
    final static float playerSelectRange = mobile ? 17f : 11f;
    final static IntSeq removed = new IntSeq();
    /** Maximum line length. */
    final static int maxLength = 100;
    final static Rect r1 = new Rect(), r2 = new Rect();

    public final OverlayFragment frag = new OverlayFragment();

    /** If any of these functions return true, input is locked. */
    public Seq<Boolp> inputLocks = Seq.with(() -> renderer.isCutscene());
    public Interval controlInterval = new Interval();
    public @Nullable Block block;
    public boolean overrideLineRotation;
    public int rotation;
    public boolean droppingItem;
    public Group uiGroup;
    public boolean isBuilding = true, buildWasAutoPaused = false, wasShooting = false;
    public @Nullable UnitType controlledType;
    public float recentRespawnTimer;

    public @Nullable Schematic lastSchematic;
    public boolean isLoadedSchematic = false; // whether it is a schematic schematic
    public GestureDetector detector;
    public PlaceLine line = new PlaceLine();
    public BuildPlan resultreq;
    public BuildPlan brequest = new BuildPlan();
    public Seq<BuildPlan> lineRequests = new Seq<>();
    public Seq<BuildPlan> selectRequests = new Seq<>();
    public boolean conveyorPlaceNormal = false;
    /** Last logic virus warning block */
    @Nullable
    public LogicBlock.LogicBuild lastVirusWarning, virusBuild;
    public long lastVirusWarnTime;
    private static Interval timer = new Interval();
    private static ChatFragment.ChatMessage commandWarning = null;

    /** Other client stuff **/
    public boolean showTypingIndicator = Core.settings.getBool("typingindicator");

    public InputHandler(){
        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit != null && e.unit.isPlayer() && e.unit.getPlayer().isLocal() && e.unit.type.weapons.contains(w -> w.bullet.killShooter)){
                player.shooting = false;
            }
        });
    }

    //methods to override

    @Remote(called = Loc.server, unreliable = true)
    public static void transferItemEffect(Item item, float x, float y, Itemsc to){
        if(to == null) return;
        createItemTransfer(item, 1, x, y, to, null);
    }

    @Remote(called = Loc.server, unreliable = true)
    public static void takeItems(Building build, Item item, int amount, Unit to){
        if(to == null || build == null) return;

        int removed = build.removeStack(item, Math.min(to.maxAccepted(item), amount));
        if(removed == 0) return;

        to.addItem(item, removed);
        for(int j = 0; j < Mathf.clamp(removed / 3, 1, 8); j++){
            Time.run(j * 3f, () -> transferItemEffect(item, build.x, build.y, to));
        }
    }

    @Remote(called = Loc.server, unreliable = true)
    public static void transferItemToUnit(Item item, float x, float y, Itemsc to){
        if(to == null) return;
        createItemTransfer(item, 1, x, y, to, () -> to.addItem(item));
    }

    @Remote(called = Loc.server, unreliable = true)
    public static void setItem(Building build, Item item, int amount){
        if(build == null || build.items == null) return;
        build.items.set(item, amount);
    }

    @Remote(called = Loc.server, unreliable = true)
    public static void clearItems(Building build){
        if(build == null || build.items == null) return;
        build.items.clear();
    }

    @Remote(called = Loc.server, unreliable = true)
    public static void transferItemTo(@Nullable Unit unit, Item item, int amount, float x, float y, Building build){
        if(build == null || build.items == null) return;

        if(unit != null && unit.item() == item) unit.stack.amount = Math.max(unit.stack.amount - amount, 0);

        for(int i = 0; i < Mathf.clamp(amount / 3, 1, 8); i++){
            Time.run(i * 3, () -> createItemTransfer(item, amount, x, y, build, () -> {}));
        }
        if(amount > 0){
            build.handleStack(item, amount, unit);
        }
    }

    @Remote(called = Loc.both, targets = Loc.both, forward = true, unreliable = true)
    public static void deletePlans(Player player, int[] positions){
        if(net.server() && !netServer.admins.allowAction(player, ActionType.removePlanned, a -> a.plans = positions)){
            throw new ValidateException(player, "Player cannot remove plans.");
        }

        if(player == null) return;

        var it = player.team().data().blocks.iterator();
        //O(n^2) search here; no way around it
        outer:
        while(it.hasNext()){
            BlockPlan req = it.next();

            for(int pos : positions){
                if(req.x == Point2.x(pos) && req.y == Point2.y(pos)){
                    req.removed = true;
                    it.remove();
                    continue outer;
                }
            }
        }
    }

    public static void createItemTransfer(Item item, int amount, float x, float y, Position to, Runnable done){
        Fx.itemTransfer.at(x, y, amount, item.color, to);
        if(done != null){
            Time.run(Fx.itemTransfer.lifetime, done);
        }
    }

    @Remote(called = Loc.server, targets = Loc.both, forward = true)
    public static void requestItem(Player player, Building build, Item item, int amount){
        if(player == null || build == null || !build.interactable(player.team()) || !player.within(build, buildingRange) || player.dead()) return;

        if(net.server() && (!Units.canInteract(player, build) ||
        !netServer.admins.allowAction(player, ActionType.withdrawItem, build.tile(), action -> {
            action.item = item;
            action.itemAmount = amount;
        }))){
            throw new ValidateException(player, "Player cannot request items.");
        }

        Navigation.addWaypointRecording(new ItemPickupWaypoint(build.tileX(), build.tileY(), new ItemStack().set(item, amount)));

        //remove item for every controlling unit
        player.unit().eachGroup(unit -> {
            Call.takeItems(build, item, Math.min(unit.maxAccepted(item), amount), unit);

            if(unit == player.unit()){
                Events.fire(new WithdrawEvent(build, player, item, amount));
            }
        });
    }

    @Remote(targets = Loc.both, forward = true, called = Loc.server)
    public static void transferInventory(Player player, Building build){
        if(player == null || build == null || !player.within(build, buildingRange) || build.items == null || player.dead()) return;

        if(net.server() && (player.unit().stack.amount <= 0 || !Units.canInteract(player, build) ||
        !netServer.admins.allowAction(player, ActionType.depositItem, build.tile, action -> {
            action.itemAmount = player.unit().stack.amount;
            action.item = player.unit().item();
        }))){
            throw new ValidateException(player, "Player cannot transfer an item.");
        }

        //deposit for every controlling unit
        player.unit().eachGroup(unit -> {
            Item item = unit.item();
            int accepted = build.acceptStack(item, unit.stack.amount, unit);

            Call.transferItemTo(unit, item, accepted, unit.x, unit.y, build);

            if(unit == player.unit()){
                Events.fire(new DepositEvent(build, player, item, accepted));
            }
        });
    }

    @Remote(variants = Variant.one)
    public static void removeQueueBlock(int x, int y, boolean breaking){
        player.unit().removeBuild(x, y, breaking);
    }

    @Remote(targets = Loc.both, called = Loc.server)
    public static void requestUnitPayload(Player player, Unit target){
        if(player == null || !(player.unit() instanceof Payloadc pay)) return;

        Unit unit = player.unit();

        if(target.isAI() && target.isGrounded() && pay.canPickup(target)
        && target.within(unit, unit.type.hitSize * 2f + target.type.hitSize * 2f)){
            Call.pickedUnitPayload(unit, target);
        }
    }

    @Remote(targets = Loc.both, called = Loc.server)
    public static void requestBuildPayload(Player player, Building build){
        if(player == null || !(player.unit() instanceof Payloadc pay)) return;

        Unit unit = player.unit();

        if(build != null && state.teams.canInteract(unit.team, build.team)
        && unit.within(build, tilesize * build.block.size * 1.2f + tilesize * 5f)){

            //pick up block's payload
            Payload current = build.getPayload();
            if(current != null && pay.canPickupPayload(current)){
                Call.pickedBuildPayload(unit, build, false);
                //pick up whole building directly
            }else if(build.block.buildVisibility != BuildVisibility.hidden && build.canPickup() && pay.canPickup(build)){
                Call.pickedBuildPayload(unit, build, true);
            }
        }
    }

    @Remote(targets = Loc.server, called = Loc.server)
    public static void pickedUnitPayload(Unit unit, Unit target){
        if(target != null && unit instanceof Payloadc pay){
            pay.pickup(target);
        }else if(target != null){
            target.remove();
        }
    }

    @Remote(targets = Loc.server, called = Loc.server)
    public static void pickedBuildPayload(Unit unit, Building build, boolean onGround){
        if(build != null && unit instanceof Payloadc pay){
            build.tile.getLinkedTiles(tile2 -> {
                ConstructBlock.breakWarning(tile2, build.block, unit);
            });

        if(onGround){
            if(build.block.buildVisibility != BuildVisibility.hidden && build.canPickup() && pay.canPickup(build)){
                pay.pickup(build);
            }else{
                Fx.unitPickup.at(build);
                build.tile.remove();
            }
        }else{
            Payload current = build.getPayload();
            if(current != null && pay.canPickupPayload(current)){
                Payload taken = build.takePayload();
                if(taken != null){
                    pay.addPayload(taken);
                    Fx.unitPickup.at(build);}
                }
            }

        }else if(build != null && onGround){
            Fx.unitPickup.at(build);
            build.tile.remove();
        }
    }

    @Remote(targets = Loc.both, called = Loc.server)
    public static void requestDropPayload(Player player, float x, float y){
        if(player == null || net.client()) return;

        Payloadc pay = (Payloadc)player.unit();

        //apply margin of error
        Tmp.v1.set(x, y).sub(pay).limit(tilesize * 4f).add(pay);
        float cx = Tmp.v1.x, cy = Tmp.v1.y;

        Call.payloadDropped(player.unit(), cx, cy);
    }

    @Remote(called = Loc.server, targets = Loc.server)
    public static void payloadDropped(Unit unit, float x, float y){
        if(unit instanceof Payloadc pay){
            float prevx = pay.x(), prevy = pay.y();
            pay.set(x, y);
            pay.dropLastPayload();
            pay.set(prevx, prevy);
            pay.controlling().each(u -> {
                if(u instanceof Payloadc){
                    Call.payloadDropped(u, u.x, u.y);
                }
            });
        }
    }

    @Remote(targets = Loc.client, called = Loc.server)
    public static void dropItem(Player player, float angle){
        if(player == null) return;

        if(net.server() && player.unit().stack.amount <= 0){
            throw new ValidateException(player, "Player cannot drop an item.");
        }

        player.unit().eachGroup(unit -> {
            Fx.dropItem.at(unit.x, unit.y, angle, Color.white, unit.item());
            unit.clearItem();
        });
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true, unreliable = true)
    public static void rotateBlock(@Nullable Player player, Building build, boolean direction){
        if(build == null) return;

        if(net.server() && (!Units.canInteract(player, build) ||
            !netServer.admins.allowAction(player, ActionType.rotate, build.tile(), action -> action.rotation = Mathf.mod(build.rotation + Mathf.sign(direction), 4)))){
            throw new ValidateException(player, "Player cannot rotate a block.");
        }

        int newRotation = Mathf.mod(build.rotation + Mathf.sign(direction), 4);

        Events.fire(new BlockRotateEvent(player, build, direction, build.rotation, newRotation));

        build.rotation = newRotation;
        build.updateProximity();
        build.noSleep();
        Fx.rotateBlock.at(build.x, build.y, build.block.size);
    }

    @Remote(targets = Loc.both, called = Loc.both, forward = true)
    public static void tileConfig(@Nullable Player player, Building build, @Nullable Object value){
        if(build == null) return;
        if(net.server() && (!Units.canInteract(player, build) ||
            !netServer.admins.allowAction(player, ActionType.configure, build.tile, action -> action.config = value))) throw new ValidateException(player, "Player cannot configure a tile.");
        else if(net.client() && player != null && Vars.player == player) ClientVars.ratelimitRemaining--; // Prevent the config queue from exceeding the rate limit if we also config stuff manually. Not quite ideal as manual configs will still exceed the limit but oh well.

        Object previous = build.config();

        Events.fire(new ConfigEventBefore(build, player, value));
        build.configured(player == null || player.dead() ? null : player.unit(), value);
        Core.app.post(() -> Events.fire(new ConfigEvent(build, player, value)));

        if (player != null /*&& Vars.player != player*/) { // FINISHME: Move all this client stuff into the ClientLogic class
            if (Core.settings.getBool("commandwarnings") && build instanceof CommandCenter.CommandBuild cmd && build.team == player.team()) {
                if (commandWarning == null || timer.get(300)) {
                    commandWarning = ui.chatfrag.addMessage(bundle.format("client.commandwarn", Strings.stripColors(player.name), cmd.tileX(), cmd.tileY(), cmd.team.data().command.localized()), (Color)null);
                } else {
                    commandWarning.message = bundle.format("client.commandwarn", Strings.stripColors(player.name), cmd.tileX(), cmd.tileY(), cmd.team.data().command.localized());
                    commandWarning.format();
                }
                lastCorePos.set(build.tileX(), build.tileY());

            } else if (Core.settings.getBool("powersplitwarnings") && build instanceof PowerNode.PowerNodeBuild node) {
                if (value instanceof Integer val) {
                    Point2 target = Point2.unpack(val).sub(build.tileX(), build.tileY());
                    for(Point2 point: (Point2[])previous){
                        if(!(target.x == point.x && target.y == point.y)) continue;
                        if(node.power.graph.all.contains(world.build(val))) continue; // if it is still in the same graph
                        String message = bundle.format("client.powerwarn", Strings.stripColors(player.name), ++node.disconnections, build.tileX(), build.tileY());
                        lastCorePos.set(build.tileX(), build.tileY());
                        if (node.message == null || ui.chatfrag.messages.indexOf(node.message) > 8) {
                            node.disconnections = 1;
                            node.message = ui.chatfrag.addMessage(message, (Color)null);
                        } else {
                            ui.chatfrag.doFade(2);
                            node.message.message = message;
                            node.message.format();
                        }
                        break;
                    }
                } else if (value instanceof Point2[]) {
                    // FINISHME: handle this
                }
            }
        }
    }

    //only useful for servers or local mods, and is not replicated across clients
    //uses unreliable packets due to high frequency
    @Remote(targets = Loc.both, called = Loc.both, unreliable = true)
    public static void tileTap(@Nullable Player player, Tile tile){
        if(tile == null) return;

        Events.fire(new TapEvent(player, tile));
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void buildingControlSelect(Player player, Building build){
        if(player == null || build == null || player.dead()) return;

        //make sure player is allowed to control the building
        if(net.server() && !netServer.admins.allowAction(player, ActionType.buildSelect, action -> action.tile = build.tile)){
            throw new ValidateException(player, "Player cannot control a building.");
        }

        if(player.team() == build.team && build.canControlSelect(player.unit())){
            build.onControlSelect(player.unit());
        }
    }

    @Remote(called = Loc.server)
    public static void unitBuildingControlSelect(Unit unit, Building build){
        if(unit == null || unit.dead()) return;

        //client skips checks to prevent ghost units
        if(unit.team() == build.team && (net.client() || build.canControlSelect(unit))){
            build.onControlSelect(unit);
        }
    }

    @Remote(targets = Loc.both, called = Loc.both, forward = true)
    public static void unitControl(Player player, @Nullable Unit unit){
        if(player == null) return;

        //make sure player is allowed to control the unit
        if(net.server() && !netServer.admins.allowAction(player, ActionType.control, action -> action.unit = unit)){
            throw new ValidateException(player, "Player cannot control a unit.");
        }


        //clear player unit when they possess a core
        if(unit == null){ //just clear the unit (is this used?)
            player.clearUnit();
            //make sure it's AI controlled, so players can't overwrite each other
        }else if(unit.isAI() && unit.team == player.team() && !unit.dead){
            if(net.client() && player.isLocal()){
                player.justSwitchFrom = player.unit();
                player.justSwitchTo = unit;
            }

            player.unit(unit);

            Time.run(Fx.unitSpirit.lifetime, () -> Fx.unitControl.at(unit.x, unit.y, 0f, unit));
            if(!player.dead()){
                Fx.unitSpirit.at(player.x, player.y, 0f, unit);
            }
        }else if(net.server()){
            //reject forwarding the packet if the unit was dead, AI or team
            throw new ValidateException(player, "Player attempted to control invalid unit.");
        }

        Events.fire(new UnitControlEvent(player, unit));
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void unitClear(Player player){
        if(player == null) return;

        //problem: this gets called on both ends. it shouldn't be.
        Fx.spawn.at(player);
        player.clearUnit();
        player.checkSpawn();
        player.deathTimer = Player.deathDelay + 1f; //for instant respawn
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void unitCommand(Player player){
        if(player == null || player.dead() || (player.unit() == null)) return;

        //make sure player is allowed to make the command
        if(net.server() && !netServer.admins.allowAction(player, ActionType.command, action -> {})){
            throw new ValidateException(player, "Player cannot command a unit.");
        }

        if(player.unit().isCommanding()){
            player.unit().clearCommand();
        }else if(player.unit().type.commandLimit > 0){

            //TODO try out some other formations
            player.unit().commandNearby(new CircleFormation());
            Fx.commandSend.at(player, player.unit().type.commandRadius);
        }
    }

    /** Adds an input lock; if this function returns true, input is locked. Used for mod 'cutscenes' or custom camera panning. */
    public void addLock(Boolp lock){
        inputLocks.add(lock);
    }

    /** @return whether most input is locked, for 'cutscenes' */
    public boolean locked(){
        return inputLocks.contains(Boolp::get);
    }

    Eachable<BuildPlan> dumb = cons -> {
        for(BuildPlan request : player.unit().plans()) cons.get(request);
        for(BuildPlan request : selectRequests) cons.get(request);
        for(BuildPlan request : lineRequests) cons.get(request);
    };
    public Eachable<BuildPlan> allRequests(){
        return dumb;
    }

    public boolean isUsingSchematic(){
        return !selectRequests.isEmpty();
    }

    public void update(){
        isLoadedSchematic &= lastSchematic != null; // i am lazy to reset it on all other instances; this should suffice
        player.typing = showTypingIndicator && ui.chatfrag.shown();

        if(player.dead()){
            droppingItem = false;
        }

//        if(player.isBuilder()){
            player.unit().updateBuilding(isBuilding);
//        }

        if(player.shooting && !wasShooting && player.unit().hasWeapons() && state.rules.unitAmmo && !player.team().rules().infiniteAmmo && player.unit().ammo <= 0){
            player.unit().type.weapons.first().noAmmoSound.at(player.unit());
        }

        //you don't want selected blocks while locked, looks weird
        if(locked()){
            block = null;
        }

        wasShooting = player.shooting;

        //only reset the controlled type and control a unit after the timer runs out
        //essentially, this means the client waits for ~1 second after controlling something before trying to control something else automatically
        if(!player.dead() && (recentRespawnTimer -= Time.delta / 70f) <= 0f && player.justSwitchFrom != player.unit()){
            controlledType = player.unit().type;
        }

        if(controlledType != null && player.dead()){
            Unit unit = Units.closest(player.team(), player.x, player.y, u -> !u.isPlayer() && u.type == controlledType && !u.dead && (!(u.controller() instanceof FormationAI f) || f.leader == player.unitOnDeath));

            if(unit != null){
                //only trying controlling once a second to prevent packet spam
                if(!net.client() || controlInterval.get(70f)){
                    recentRespawnTimer = 1f;
                    Call.unitControl(player, unit);
                }
            }
        }
    }

    public void checkUnit(){
        if(controlledType != null){
            Unit unit = Units.closest(player.team(), player.x, player.y, u -> !u.isPlayer() && u.type == controlledType && !u.dead);
            if(unit == null && controlledType == UnitTypes.block){
                unit = world.buildWorld(player.x, player.y) instanceof ControlBlock cont && cont.canControl() ? cont.unit() : null;
            }

            if(unit != null){
                if(net.client()){
                    Call.unitControl(player, unit);
                }else{
                    unit.controller(player);
                }
            }
        }
    }

    public void tryPickupPayload(){
        Unit unit = player.unit();
        if(!(unit instanceof Payloadc pay)) return;

        Unit target = Units.closest(player.team(), pay.x(), pay.y(), unit.type.hitSize * 2f, u -> u.isAI() && u.isGrounded() && pay.canPickup(u) && u.within(unit, u.hitSize + unit.hitSize));
        if(target != null){
            Call.requestUnitPayload(player, target);
        }else{
            Building build = world.buildWorld(pay.x(), pay.y());

            if(build != null && state.teams.canInteract(unit.team, build.team)){
                Call.requestBuildPayload(player, build);
                if(Navigation.state == NavigationState.RECORDING){
                    Navigation.addWaypointRecording(new PayloadPickupWaypoint(build.tileX(), build.tileY()));
                }
            }
        }
    }

    public void tryDropPayload(){
        Unit unit = player.unit();
        if(!(unit instanceof Payloadc)) return;

        Call.requestDropPayload(player, player.x, player.y);
        if(Navigation.state == NavigationState.RECORDING){
            Navigation.addWaypointRecording(new PayloadDropoffWaypoint(player.tileX(), player.tileY()));
        }
    }

    public float getMouseX(){
        return Core.input.mouseX();
    }

    public float getMouseY(){
        return Core.input.mouseY();
    }

    public void buildPlacementUI(Table table){

    }

    public void buildUI(Group group){

    }

    public void updateState(){
        if(state.isMenu()){
            controlledType = null;
        }
    }

    public void drawBottom(){

    }

    public void drawTop(){

    }

    public void drawOverSelect(){

    }

    public void drawSelected(int x, int y, Block block, Color color){
        Drawf.selected(x, y, block, color);
    }

    public void drawBreaking(BuildPlan request){
        if(request.breaking){
            drawBreaking(request.x, request.y);
        }else{
            drawSelected(request.x, request.y, request.block, Pal.remove);
        }
    }

    public boolean requestMatches(BuildPlan request){
        Tile tile = world.tile(request.x, request.y);
        return tile != null && tile.build instanceof ConstructBuild cons && cons.current == request.block;
    }

    public void drawBreaking(int x, int y){
        Tile tile = world.tile(x, y);
        if(tile == null) return;
        Block block = tile.block();

        drawSelected(x, y, block, Pal.remove);
    }

    public void drawFreezing(BuildPlan request){
        if(world.tile(request.x, request.y) == null) return;
        drawSelected(request.x, request.y, request.block, Pal.freeze); // bypass check if plan overlaps with existing block
    }

    public void useSchematic(Schematic schem){
        selectRequests.addAll(schematics.toRequests(schem, player.tileX(), player.tileY()));
    }

    protected void showSchematicSave(){
        if(lastSchematic == null) return;

        ui.showTextInput("@schematic.add", "@name", "", text -> {
            Schematic replacement = schematics.all().find(s -> s.name().equals(text));
            if(replacement != null){
                ui.showConfirm("@confirm", "@schematic.replace", () -> {
                    schematics.overwrite(replacement, lastSchematic);
                    ui.showInfoFade("@schematic.saved");
                    ui.schematics.showInfo(replacement);
                });
            }else{
                lastSchematic.tags.put("name", text);
                lastSchematic.tags.put("description", "");
                schematics.add(lastSchematic);
                ui.showInfoFade("@schematic.saved");
                ui.schematics.showInfo(lastSchematic);
                Events.fire(new SchematicCreateEvent(lastSchematic));
            }
        });
    }

    public void rotateRequests(Seq<BuildPlan> requests, int direction){
        int ox = schemOriginX(), oy = schemOriginY();

        requests.each(req -> {
            if(req.breaking) return;

            req.pointConfig(p -> {
                int cx = p.x, cy = p.y;
                int lx = cx;

                if(direction >= 0){
                    cx = -cy;
                    cy = lx;
                }else{
                    cx = cy;
                    cy = -lx;
                }
                p.set(cx, cy);
            });

            //rotate actual request, centered on its multiblock position
            float wx = (req.x - ox) * tilesize + req.block.offset, wy = (req.y - oy) * tilesize + req.block.offset;
            float x = wx;
            if(direction >= 0){
                wx = -wy;
                wy = x;
            }else{
                wx = wy;
                wy = -x;
            }
            req.x = World.toTile(wx - req.block.offset) + ox;
            req.y = World.toTile(wy - req.block.offset) + oy;
            req.rotation = Mathf.mod(req.rotation + direction, 4);
        });
    }

    public void flipRequests(Seq<BuildPlan> requests, boolean x){
        int origin = (x ? schemOriginX() : schemOriginY()) * tilesize;

        requests.each(req -> {
            if(req.breaking) return;

            float value = -((x ? req.x : req.y) * tilesize - origin + req.block.offset) + origin;

            if(x){
                req.x = (int)((value - req.block.offset) / tilesize);
            }else{
                req.y = (int)((value - req.block.offset) / tilesize);
            }

            req.pointConfig(p -> {
                int corigin = x ? req.originalWidth/2 : req.originalHeight/2;
                int nvalue = -(x ? p.x : p.y);
                if(x){
                    req.originalX = -(req.originalX - corigin) + corigin;
                    p.x = nvalue;
                }else{
                    req.originalY = -(req.originalY - corigin) + corigin;
                    p.y = nvalue;
                }
            });

            //flip rotation
            req.block.flipRotation(req, x);
        });
    }

    protected int schemOriginX(){
        return rawTileX();
    }

    protected int schemOriginY(){
        return rawTileY();
    }

    /** Returns the selection request that overlaps this position, or null. */
    protected BuildPlan getRequest(int x, int y){
        return getRequest(x, y, 1, null);
    }

    /** Returns the selection request that overlaps this position, or null. */
    protected BuildPlan getRequest(int x, int y, int size, BuildPlan skip){
        float offset = ((size + 1) % 2) * tilesize / 2f;
        r2.setSize(tilesize * size);
        r2.setCenter(x * tilesize + offset, y * tilesize + offset);
        resultreq = null;

        Boolf<BuildPlan> test = req -> {
            if(req == skip) return false;
            Tile other = req.tile();

            if(other == null) return false;

            if(!req.breaking){
                r1.setSize(req.block.size * tilesize);
                r1.setCenter(other.worldx() + req.block.offset, other.worldy() + req.block.offset);
            }else{
                r1.setSize(other.block().size * tilesize);
                r1.setCenter(other.worldx() + other.block().offset, other.worldy() + other.block().offset);
            }

            return r2.overlaps(r1);
        };

        for(BuildPlan req : player.unit().plans()){
            if(test.get(req)) return req;
        }

        return selectRequests.find(test);
    }

    protected void drawFreezeSelection(int x1, int y1, int x2, int y2, int maxLength){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);

        Tmp.r1.set(result.x, result.y, result.x2 - result.x, result.y2 - result.y);

        Draw.color(Pal.freeze);
        Lines.stroke(1f);

        for(BuildPlan req: player.unit().plans()){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawFreezing(req);
            }
        }
        for(BuildPlan req: selectRequests){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawFreezing(req);
            }
        }

        Draw.reset();
        Draw.color(Pal.freeze);
        Draw.alpha(0.3f);
        float x = (result.x2 + result.x) / 2;
        float y = (result.y2 + result.y) / 2;
        Fill.rect(x, y, result.x2 - result.x, result.y2 - result.y);
    }

    protected void drawBreakSelection(int x1, int y1, int x2, int y2, int maxLength){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);
        NormalizeResult dresult = Placement.normalizeArea(x1, y1, x2, y2, rotation, false, maxLength);

        for(int x = dresult.x; x <= dresult.x2; x++){
            for(int y = dresult.y; y <= dresult.y2; y++){
                Tile tile = world.tileBuilding(x, y);
                if(tile == null || !validBreak(tile.x, tile.y)) continue;

                drawBreaking(tile.x, tile.y);
            }
        }

        Tmp.r1.set(result.x, result.y, result.x2 - result.x, result.y2 - result.y);

        Draw.color(Pal.remove);
        Lines.stroke(1f);

        for(BuildPlan req : player.unit().plans()){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawBreaking(req);
            }
        }

        for(BuildPlan req : selectRequests){
            if(req.breaking) continue;
            if(req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                drawBreaking(req);
            }
        }

        for (BlockPlan req : player.team().data().blocks) {
            Block block = content.block(req.block);
            if (block.bounds(req.x, req.y, Tmp.r2).overlaps(Tmp.r1)) {
                drawSelected(req.x, req.y, content.block(req.block), Pal.remove);
            }
        }

        Draw.color(Pal.remove);
        Draw.alpha(0.3f);
        float x = (result.x2 + result.x) / 2;
        float y = (result.y2 + result.y) / 2;
        Fill.rect(x, y, result.x2 - result.x, result.y2 - result.y);
    }

    protected void drawBreakSelection(int x1, int y1, int x2, int y2){
        drawBreakSelection(x1, y1, x2, y2, maxLength);
    }

    protected void drawSelection(int x1, int y1, int x2, int y2, int maxLength){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);

        Draw.color(Pal.accent);
        Draw.alpha(0.3f);
        float x = (result.x2 + result.x) / 2;
        float y = (result.y2 + result.y) / 2;
        Fill.rect(x, y, result.x2 - result.x, result.y2 - result.y);
    }

    protected void flushSelectRequests(Seq<BuildPlan> requests){
        for(BuildPlan req : requests){
            if(req.block != null && validPlace(req.x, req.y, req.block, req.rotation)){
                BuildPlan other = getRequest(req.x, req.y, req.block.size, null);
                if(other == null){
                    selectRequests.add(req.copy());
                }else if(!other.breaking && other.x == req.x && other.y == req.y && other.block.size == req.block.size){
                    selectRequests.remove(other);
                    selectRequests.add(req.copy());
                }
            }
        }
    }

    private final Seq<Tile> tempTiles = new Seq<>(4);
    protected void flushRequests(Seq<BuildPlan> requests){
        var configLogic = Core.settings.getBool("processorconfigs");
        var temp = new BuildPlan[requests.size + requests.count(req -> req.block == Blocks.waterExtractor) * 3];
        var added = 0;
        for(BuildPlan req : requests){
            if (req.block == null) continue;

            if (req.block == Blocks.waterExtractor && !input.shift() // Attempt to replace water extractors with pumps FINISHME: Don't place 4 pumps, only 2 needed
                    && req.tile() != null && req.tile().getLinkedTilesAs(req.block, tempTiles).contains(t -> t.floor().liquidDrop == Liquids.water)) { // Has water
                var first = tempTiles.first();
                var replaced = false;
                if (tempTiles.contains(t -> !t.adjacentTo(first) && t != first && t.floor().liquidDrop == Liquids.water)) { // Can use mechanical pumps (covers all outputs)
                    for (var t : tempTiles) {
                        var plan = new BuildPlan(t.x, t.y, 0, t.floor().liquidDrop == Liquids.water ? Blocks.mechanicalPump : Blocks.liquidJunction);
                        if (validPlace(t.x, t.y, plan.block, 0)) {
                            req.block.onNewPlan(req);
                            temp[added++] = req;
                            replaced = true;
                        }
                    }
                } else if (validPlace(first.x, first.y, Blocks.rotaryPump, 0)) { // Mechanical pumps can't cover everything, use rotary pump instead
                    player.unit().addBuild(new BuildPlan(req.x, req.y, 0, Blocks.rotaryPump));
                    replaced = true;
                }
                if (replaced) continue; // Swapped water extractor for pump, don't place it
            }

            if(validPlace(req.x, req.y, req.block, req.rotation)){
                BuildPlan copy = req.copy();
                if(configLogic && copy.block instanceof LogicBlock && copy.config != null){
                    final var conf = copy.config; // this is okay because processor connections are relative
                    copy.config = null;
                    copy.clientConfig = it -> {
                        if (!(it instanceof LogicBlock.LogicBuild build)) return;
                        if (!build.code.isEmpty() || build.links.any())
                            return; // Someone else built a processor with data
                        configs.add(new ConfigRequest(it.tile.x, it.tile.y, conf));
                    };
                }
                if(copy.block instanceof PowerNode && copy.config instanceof Point2[] conf){
                    int requiredSetting = (isLoadedSchematic ? PowerNodeFixSettings.enableReq : PowerNodeFixSettings.nonSchematicReq) + (copy.block instanceof PowerSource ? 1 : 0);
                    if (PowerNodeBuild.fixNode >= requiredSetting) {
                        final var nconf = new Point2[conf.length];
                        for (int i = 0; i < conf.length; i++) nconf[i] = conf[i].cpy();
                        copy.clientConfig = it -> {
                            if (it instanceof PowerNodeBuild build) build.fixNode(nconf);
                        };
                    }
                }
                req.block.onNewPlan(copy);
                temp[added++] = copy;
            }
            Iterator<BuildPlan> it = frozenPlans.iterator();
            while(it.hasNext()){
                BuildPlan frz = it.next();
                if(req.block.bounds(req.x, req.y, Tmp.r1).overlaps(frz.block.bounds(frz.x, frz.y, Tmp.r2))){
                    it.remove();
                }
            }
        }

        for (int i = 0; i < added; i++) player.unit().addBuild(temp[i]);
    }

    protected void drawOverRequest(BuildPlan request, boolean valid){
        if(!request.isVisible()) return;
        Draw.reset();
        final long frameId = graphics.getFrameId();
        if(lastFrameVisible != frameId){
            lastFrameVisible = frameId;
            visiblePlanSeq.clear();
            BuildPlan.getVisiblePlans(cons -> {
                selectRequests.each(cons);
                lineRequests.each(cons);
            }, visiblePlanSeq);
        }
        Draw.mixcol(!valid ? Pal.breakInvalid : Color.white, (!valid ? 0.4f : 0.24f) + Mathf.absin(Time.globalTime, 6f, 0.28f));
        Draw.alpha(1f);
        request.block.drawRequestConfigTop(request, visiblePlanSeq);
        Draw.reset();
    }

    protected void drawRequest(BuildPlan request, boolean valid){
        request.block.drawPlan(request, allRequests(), valid);
    }

    /** Draws a placement icon for a specific block. */
    protected void drawRequest(int x, int y, Block block, int rotation){
        brequest.set(x, y, rotation, block);
        brequest.animScale = 1f;
        block.drawPlan(brequest, allRequests(), validPlace(x, y, block, rotation));
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2){
        removeSelection(x1, y1, x2, y2, false);
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2, int maxLength){
        removeSelection(x1, y1, x2, y2, false, maxLength);
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2, boolean flush){
        removeSelection(x1, y1, x2, y2, flush, maxLength);
    }

    /** Remove everything from the queue in a selection. */
    protected void removeSelection(int x1, int y1, int x2, int y2, boolean flush, int maxLength){
        NormalizeResult result = Placement.normalizeArea(x1, y1, x2, y2, rotation, false, maxLength);
        for(int x = 0; x <= Math.abs(result.x2 - result.x); x++){
            for(int y = 0; y <= Math.abs(result.y2 - result.y); y++){
                int wx = x1 + x * Mathf.sign(x2 - x1);
                int wy = y1 + y * Mathf.sign(y2 - y1);

                Tile tile = world.tileBuilding(wx, wy);

                if(tile == null) continue;

                if(!flush){
                    tryBreakBlock(wx, wy);
                }else if(validBreak(tile.x, tile.y) && !selectRequests.contains(r -> r.tile() != null && r.tile() == tile)){
                    selectRequests.add(new BuildPlan(tile.x, tile.y));
                }
            }
        }

        //remove build requests
        Tmp.r1.set(result.x * tilesize, result.y * tilesize, (result.x2 - result.x) * tilesize, (result.y2 - result.y) * tilesize);

        Iterator<BuildPlan> it = player.unit().plans().iterator();
        while(it.hasNext()){
            BuildPlan req = it.next();
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                it.remove();
            }
        }

        it = selectRequests.iterator();
        while(it.hasNext()){
            BuildPlan req = it.next();
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)){
                it.remove();
            }
        }

        removed.clear();

        //remove blocks to rebuild
        Iterator<BlockPlan> broken = player.team().data().blocks.iterator();
        while (broken.hasNext()) {
            BlockPlan req = broken.next();
            Block block = content.block(req.block);
            if (block.bounds(req.x, req.y, Tmp.r2).overlaps(Tmp.r1)){
                removed.add(Point2.pack(req.x, req.y));
                req.removed = true;
                broken.remove();
            }
        }

        //TODO array may be too large?
        if(removed.size > 0 && net.active()){
            Call.deletePlans(player, removed.toArray());
        }
    }

    /** Freeze all schematics in a selection. */
    protected void freezeSelection(int x1, int y1, int x2, int y2, int maxLength){
        freezeSelection(x1, y1, x2, y2, false, maxLength);
    }

    /** Helper function with changing from the first Seq to the next. Used to be a BiPredicate but moved out **/
    private boolean checkFreezeSelectionHasNext(BuildPlan frz, Iterator<BuildPlan> it){
        boolean hasNext;
        while((hasNext = it.hasNext()) && it.next() != frz) ; // skip to the next instance when it.next() == frz
        if(hasNext) it.remove();
        return hasNext;
    }

    protected void freezeSelection(int x1, int y1, int x2, int y2, boolean flush, int maxLength){
        NormalizeResult result = Placement.normalizeArea(x1, y1, x2, y2, rotation, false, maxLength);

        Seq<BuildPlan> tmpFrozenPlans = new Seq<>();
        //remove build requests
        Tmp.r1.set(result.x * tilesize, result.y * tilesize, (result.x2 - result.x) * tilesize, (result.y2 - result.y) * tilesize);

        for(BuildPlan req : player.unit().plans()){
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)) tmpFrozenPlans.add(req);
        }

        for(BuildPlan req : selectRequests){
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)) tmpFrozenPlans.add(req);
        }

        Seq<BuildPlan> unfreeze = new Seq<>();
        for(BuildPlan req : frozenPlans){
            if(!req.breaking && req.bounds(Tmp.r2).overlaps(Tmp.r1)) unfreeze.add(req);
        }

        Iterator<BuildPlan> it1, it2;
        if(unfreeze.size > tmpFrozenPlans.size){
            it1 = frozenPlans.iterator();
            for(BuildPlan frz : unfreeze){
                while(it1.hasNext() && it1.next() != frz);
                if(it1.hasNext()) it1.remove();
            }
            flushRequests(unfreeze);
        }
        else{
            it1 = player.unit().plans().iterator();
            it2 = selectRequests.iterator();
            for (BuildPlan frz : tmpFrozenPlans) {
                if(checkFreezeSelectionHasNext(frz, it1)) continue;
                if(/*!itHasNext implied*/ it2 != null){
                    it1 = it2;
                    it2 = null; // swap it2 into it1, continue iterating through without changing frz
                    if(checkFreezeSelectionHasNext(frz, it1)) continue;
                }
                break; // exit if there are no remaining items in the two Seq's to check.
            }
            frozenPlans.addAll(tmpFrozenPlans);
        }
    }

    protected void updateLine(int x1, int y1, int x2, int y2){
        lineRequests.clear();
        iterateLine(x1, y1, x2, y2, l -> {
            rotation = l.rotation;
            BuildPlan req = new BuildPlan(l.x, l.y, l.rotation, block, block.nextConfig());
            req.animScale = 1f;
            lineRequests.add(req);
        });

        if(Core.settings.getBool("blockreplace") != control.input.conveyorPlaceNormal){
            lineRequests.each(req -> {
                Block replace = req.block.getReplacement(req, lineRequests);
                if (replace.unlockedNow()) {
                    req.block = replace;
                }
            });

            block.handlePlacementLine(lineRequests);
        } else if(block instanceof ItemBridge && Core.input.shift()) block.handlePlacementLine(lineRequests);
    }

    protected void updateLine(int x1, int y1){
        updateLine(x1, y1, tileX(getMouseX()), tileY(getMouseY()));
    }

    /** Handles tile tap events that are not platform specific. */
    boolean tileTapped(@Nullable Building build){
        if(build == null){
            frag.inv.hide();
            frag.config.hideConfig();
            return false;
        }
        boolean consumed = false, showedInventory = false;

        //check if tapped block is configurable
        if(build.block.configurable && (build.interactable(player.team()) || build.block instanceof LogicBlock)){
            consumed = true;
            if((!frag.config.isShown() && build.shouldShowConfigure(player)) //if the config fragment is hidden, show
            //alternatively, the current selected block can 'agree' to switch config tiles
            || (frag.config.isShown() && frag.config.getSelectedTile().onConfigureTileTapped(build))){
                Sounds.click.at(build);
                frag.config.showConfig(build);
            }
            //otherwise...
        }else if(!frag.config.hasConfigMouse()){ //make sure a configuration fragment isn't on the cursor
            //then, if it's shown and the current block 'agrees' to hide, hide it.
            if(frag.config.isShown() && frag.config.getSelectedTile().onConfigureTileTapped(build)){
                consumed = true;
                frag.config.hideConfig();
            }

            if(frag.config.isShown()){
                consumed = true;
            }
        }

        //call tapped event
        if(!consumed && build.interactable(player.team())){
            build.tapped();
        }

        //consume tap event if necessary
        var invBuild = !build.block.hasItems && build.getPayload() instanceof BuildPayload pay ? pay.build : build;
        if(build.interactable(player.team()) && build.block.consumesTap){
            consumed = true;
        }else if(/*build.interactable(player.team()) &&*/ build.block.synthetic() && (!consumed || invBuild.block.allowConfigInventory)){
            if(invBuild.block.hasItems && invBuild.items.total() > 0){
                frag.inv.showFor(invBuild);
                consumed = true;
                showedInventory = true;
            }
        }

        if(!showedInventory){
            frag.inv.hide();
        }

        return consumed;
    }

    /** Tries to select the player to drop off items, returns true if successful. */
    boolean tryTapPlayer(float x, float y){
        if(canTapPlayer(x, y)){
            droppingItem = true;
            return true;
        }
        return false;
    }

    boolean canTapPlayer(float x, float y){
        return player.within(x, y, playerSelectRange) && player.unit().stack.amount > 0;
    }

    /** Tries to begin mining a tile, returns true if successful. */
    boolean tryBeginMine(Tile tile){
        if(canMine(tile)){
            player.unit().mineTile = tile;
            return true;
        }
        return false;
    }

    /** Tries to stop mining, returns true if mining was stopped. */
    boolean tryStopMine(){
        if(player.unit().mining()){
            player.unit().mineTile = null;
            return true;
        }
        return false;
    }

    boolean tryStopMine(Tile tile){
        if(player.unit().mineTile == tile){
            player.unit().mineTile = null;
            return true;
        }
        return false;
    }

    boolean canMine(Tile tile){
        return !Core.scene.hasMouse()
            && tile.drop() != null
            && player.unit().validMine(tile)
            && !((!Core.settings.getBool("doubletapmine") && tile.floor().playerUnmineable) && tile.overlay().itemDrop == null)
            && player.unit().acceptsItem(tile.drop())
            && tile.block() == Blocks.air;
    }

    /** Returns the tile at the specified MOUSE coordinates. */
    Tile tileAt(float x, float y){
        return world.tile(tileX(x), tileY(y));
    }

    public Tile cursorTile(){
        return world.tileWorld(input.mouseWorldX(), input.mouseWorldY());
    }

    public int rawTileX(){
        return World.toTile(Core.input.mouseWorld().x);
    }

    public int rawTileY(){
        return World.toTile(Core.input.mouseWorld().y);
    }

    public int tileX(float cursorX){
        Vec2 vec = Core.input.mouseWorld(cursorX, 0);
        if(selectedBlock()){
            vec.sub(block.offset, block.offset);
        }
        return World.toTile(vec.x);
    }

    public int tileY(float cursorY){
        Vec2 vec = Core.input.mouseWorld(0, cursorY);
        if(selectedBlock()){
            vec.sub(block.offset, block.offset);
        }
        return World.toTile(vec.y);
    }

    public boolean selectedBlock(){
        return isPlacing();
    }

    public boolean isPlacing(){
        return block != null;
    }

    public boolean isBreaking(){
        return false;
    }

    public float mouseAngle(float x, float y){
        return Core.input.mouseWorld(getMouseX(), getMouseY()).sub(x, y).angle();
    }

    public @Nullable Unit selectedUnit(boolean allowPlayers){
        boolean hidingAirUnits = ClientVars.hidingAirUnits;
        Unit unit = Units.closest(player.team(), Core.input.mouseWorld().x, Core.input.mouseWorld().y, input.shift() ? 100f : 40f,
                allowPlayers ? hidingAirUnits ? u -> !u.isLocal() && !u.isFlying() : u -> !u.isLocal()
                        : hidingAirUnits ? u -> u.isAI() && !u.isFlying() : Unitc::isAI);
        if(unit != null && !ClientVars.hidingUnits){
            unit.hitbox(Tmp.r1);
            Tmp.r1.grow(input.shift() ? tilesize * 6 : 6f ); // If shift is held, add 3 tiles of leeway, makes it easier to shift click units controlled by processors and such
            if(Tmp.r1.contains(Core.input.mouseWorld())){
                return unit;
            }
        }

        Building build = world.buildWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y);
        if(build instanceof ControlBlock cont && cont.canControl() && build.team == player.team() && cont.unit() != player.unit()){
            return cont.unit();
        }

        return null;
    }

    public @Nullable Unit selectedUnit() {
        return selectedUnit(false);
    }

    public @Nullable Building selectedControlBuild(){
        Building build = world.buildWorld(Core.input.mouseWorld().x, Core.input.mouseWorld().y);
        if(build != null && !player.dead() && build.canControlSelect(player.unit()) && build.team == player.team()){
            return build;
        }
        return null;
    }

    public void remove(){
        Core.input.removeProcessor(this);
        frag.remove();
        if(Core.scene != null){
            Table table = (Table)Core.scene.find("inputTable");
            if(table != null){
                table.clear();
            }
        }
        if(detector != null){
            Core.input.removeProcessor(detector);
        }
        if(uiGroup != null){
            uiGroup.remove();
            uiGroup = null;
        }
    }

    public void add(){
        Core.input.getInputProcessors().remove(i -> i instanceof InputHandler || (i instanceof GestureDetector && ((GestureDetector)i).getListener() instanceof InputHandler));
        Core.input.addProcessor(detector = new GestureDetector(20, 0.5f, 0.3f, 0.15f, this));
        Core.input.addProcessor(this);
        if(Core.scene != null){
            Table table = (Table)Core.scene.find("inputTable");
            if(table != null){
                table.clear();
                buildPlacementUI(table);
            }

            uiGroup = new WidgetGroup();
            uiGroup.touchable = Touchable.childrenOnly;
            uiGroup.setFillParent(true);
            ui.hudGroup.addChild(uiGroup);
            uiGroup.toBack();
            buildUI(uiGroup);

            frag.add();
        }
    }

    public boolean canShoot(){
        return !onConfigurable() && !isDroppingItem() && !player.unit().activelyBuilding() &&
            !(player.unit() instanceof Mechc && player.unit().isFlying()) && !player.unit().mining();
    }

    public boolean onConfigurable(){
        return false;
    }

    public boolean isDroppingItem(){
        return droppingItem;
    }

    public boolean canDropItem(){
        return droppingItem && !canTapPlayer(Core.input.mouseWorldX(), Core.input.mouseWorldY());
    }

    public void tryDropItems(@Nullable Building build, float x, float y){
        if(!droppingItem || player.unit().stack.amount <= 0 || canTapPlayer(x, y) || state.isPaused()){
            droppingItem = false;
            return;
        }

        droppingItem = false;

        ItemStack stack = player.unit().stack;

        var invBuild = build != null && !build.block.hasItems && build.getPayload() instanceof BuildPayload pay && pay.build.block.hasItems ? pay.build : build;
        if(invBuild != null && invBuild.acceptStack(stack.item, stack.amount, player.unit()) > 0 && invBuild.interactable(player.team()) && invBuild.block.hasItems && player.unit().stack().amount > 0 && invBuild.interactable(player.team())){
            if(Navigation.state == NavigationState.RECORDING) Navigation.addWaypointRecording(new ItemDropoffWaypoint(build)); // FINISHME: This is going to be problematic
            Call.transferInventory(player, invBuild);
        }else{
            Call.dropItem(player.angleTo(x, y));
        }
    }

    public void tryBreakBlock(int x, int y){
        if(validBreak(x, y)){
            breakBlock(x, y);
        }
    }

    public boolean validPlace(int x, int y, Block type, int rotation){
        return validPlace(x, y, type, rotation, null);
    }

    private long lastFrame, lastFrameVisible;
    private QuadTreeMk2<BuildPlan> tree = new QuadTreeMk2<>(new Rect(0, 0, 0, 0));
    public final Seq<BuildPlan> planSeq = new Seq<>(), visiblePlanSeq = new Seq<>();

    public boolean planTreeNeedsRecalculation(){
        return lastFrame == graphics.getFrameId();
    }
    /** Cursed method to put the player's plans in a quadtree for non-slow overlap checks. */
    public QuadTreeMk2<BuildPlan> planTree() {
        if(lastFrame == graphics.getFrameId()) return tree;
        lastFrame = graphics.getFrameId();

        tree.clear();
        if (world.unitWidth() != tree.bounds.width || world.unitHeight() != tree.bounds.height)
            tree = new QuadTreeMk2<>(new Rect(0, 0, world.unitWidth(), world.unitHeight()));
        var plans = player.unit().plans();
        for (int i = 0; i < plans.size; i++)
            tree.insert(plans.get(i));

        return tree;
    }

    public boolean validPlace(int x, int y, Block type, int rotation, BuildPlan ignore){
        if (!Build.validPlace(type, player.team(), x, y, rotation, true)) return false;

        planSeq.clear();
        planTree().intersect(type.bounds(x, y, Tmp.r2), planSeq);
        for (int i = 0; i < planSeq.size; i++) {
            BuildPlan req = planSeq.get(i);

            if(req != ignore
                    && !req.breaking
                    && req.block.bounds(req.x, req.y, Tmp.r1).overlaps(Tmp.r2)
                    && !(type.canReplace(req.block) && Tmp.r1.equals(Tmp.r2))){
                return false;
            }
        }
        return true;
    }

    public boolean validBreak(int x, int y){
        return Build.validBreak(player.team(), x, y);
    }

    public void breakBlock(int x, int y){
        Tile tile = world.tile(x, y);
        if(tile != null && tile.build != null) tile = tile.build.tile;
        player.unit().addBuild(new BuildPlan(tile.x, tile.y));
    }

    public void drawArrow(Block block, int x, int y, int rotation){
        drawArrow(block, x, y, rotation, validPlace(x, y, block, rotation));
    }

    public void drawArrow(Block block, int x, int y, int rotation, boolean valid){
        float trns = (block.size / 2) * tilesize;
        int dx = Geometry.d4(rotation).x, dy = Geometry.d4(rotation).y;

        Draw.color(!valid ? Pal.removeBack : Pal.accentBack);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset + dx*trns,
        y * tilesize + block.offset - 1 + dy*trns,
        Core.atlas.find("place-arrow").width * Draw.scl,
        Core.atlas.find("place-arrow").height * Draw.scl, rotation * 90 - 90);

        Draw.color(!valid ? Pal.remove : Pal.accent);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset + dx*trns,
        y * tilesize + block.offset + dy*trns,
        Core.atlas.find("place-arrow").width * Draw.scl,
        Core.atlas.find("place-arrow").height * Draw.scl, rotation * 90 - 90);
    }

    void iterateLine(int startX, int startY, int endX, int endY, Cons<PlaceLine> cons){
        Seq<Point2> points;
        boolean diagonal = Core.input.keyDown(Binding.diagonal_placement);

        if(Core.settings.getBool("swapdiagonal") && mobile){
            diagonal = !diagonal;
        }

        if(block != null && block.swapDiagonalPlacement){
            diagonal = !diagonal;
        }

        int endRotation = -1;
        if(diagonal){
            var start = world.build(startX, startY);
            var end = world.build(endX, endY);
            if(block != null && start instanceof ChainedBuilding && end instanceof ChainedBuilding
                    && block.canReplace(end.block) && block.canReplace(start.block)){
                points = Placement.upgradeLine(startX, startY, endX, endY);
                endRotation = end.rotation;
            }else{
                points = Placement.pathfindLine(block != null && block.conveyorPlacement, startX, startY, endX, endY);
            }
        }else{
            points = Placement.normalizeLine(startX, startY, endX, endY);
        }

        if(block != null){
            block.changePlacementPath(points, rotation);
        }

        float angle = Angles.angle(startX, startY, endX, endY);
        int baseRotation = rotation;
        if(!overrideLineRotation || diagonal){
            baseRotation = (startX == endX && startY == endY) ? rotation : ((int)((angle + 45) / 90f)) % 4;
        }

        Tmp.r3.set(-1, -1, 0, 0);

        for(int i = 0; i < points.size; i++){
            Point2 point = points.get(i);

            if(block != null && Tmp.r2.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset, point.y * tilesize + block.offset).overlaps(Tmp.r3)){
                continue;
            }

            Point2 next = i == points.size - 1 ? null : points.get(i + 1);
            line.x = point.x;
            line.y = point.y;
            if(!overrideLineRotation || diagonal){
                int result = baseRotation;
                if(next != null){
                    result = Tile.relativeTo(point.x, point.y, next.x, next.y);
                }else if(endRotation != -1){
                    result = endRotation;
                }else if(block.conveyorPlacement && i > 0){
                    Point2 prev = points.get(i - 1);
                    result = Tile.relativeTo(prev.x, prev.y, point.x, point.y);
                }
                if(result != -1){
                    line.rotation = result;
                }
            }else{
                line.rotation = rotation;
            }
            line.last = next == null;
            cons.get(line);

            Tmp.r3.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset, point.y * tilesize + block.offset);
        }
    }

    public void updateMovementCustom(Unit unit, float x, float y, float direction){
        if (unit == null || player.dead()) {
            return;
        }
        Vec2 movement = new Vec2();
        boolean omni = unit.type().omniMovement;
        boolean ground = unit.isGrounded();

        float strafePenalty = ground ? 1f : Mathf.lerp(1f, unit.type().strafePenalty, Angles.angleDist(unit.vel().angle(), unit.rotation()) / 180f);
        float baseSpeed = unit.type().speed;

        //limit speed to minimum formation speed to preserve formation
        if(unit.isCommanding()){
            //add a tiny multiplier to let units catch up just in case
            baseSpeed = unit.minFormationSpeed() * 0.95f;
        }

        float speed = baseSpeed * Mathf.lerp(1f, unit.type().canBoost ? unit.type().boostMultiplier : 1f, unit.elevation) * strafePenalty;
        boolean boosted = (unit instanceof Mechc && unit.isFlying());

        movement.set(x, y).nor().scl(speed);
        if(Core.input.keyDown(Binding.mouse_move)){
            movement.add(input.mouseWorld().sub(player).scl(1f / 25f * speed)).limit(speed);
        }

        boolean aimCursor = omni && player.shooting && unit.type().hasWeapons() && unit.type().faceTarget && !boosted && unit.type().rotateShooting;

        if(aimCursor){
            unit.lookAt(direction);
        }else{
            if(!movement.isZero()){
                unit.lookAt(unit.vel.isZero() ? movement.angle() : unit.vel.angle());
            }
        }

        if(omni){
            unit.moveAt(movement);
        }else{
            unit.moveAt(Tmp.v2.trns(unit.rotation, movement.len()));
            if(!movement.isZero() && ground){
                unit.vel.rotateTo(movement.angle(), unit.type().rotateSpeed);
            }
        }

        unit.aim(unit.type().faceTarget ? Core.input.mouseWorld() : Tmp.v1.trns(unit.rotation, Core.input.mouseWorld().dst(unit)).add(unit.x, unit.y));
        unit.controlWeapons(true, player.shooting && !boosted);

        player.boosting = !movement.isZero();
        player.mouseX = unit.aimX();
        player.mouseY = unit.aimY();
    }

    static class PlaceLine{
        public int x, y, rotation;
        public boolean last;
    }
}
