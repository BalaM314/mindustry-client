package mindustry.core;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.mod.*;
import mindustry.net.*;
import mindustry.net.Net.*;
import mindustry.type.*;
import mindustry.ui.dialogs.*;
import rhino.*;

import java.net.*;

import static mindustry.Vars.*;

public interface Platform{

    /** Dynamically creates a class loader for a jar file. This loader must be child-first. */
    default ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
        return new URLClassLoader(new URL[]{jar.file().toURI().toURL()}, parent){
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                //check for loaded state
                Class<?> loadedClass = findLoadedClass(name);
                if(loadedClass == null){
                    try{
                        //try to load own class first
                        loadedClass = findClass(name);
                    }catch(ClassNotFoundException e){
                        //use parent if not found
                        return parent.loadClass(name);
                    }
                }

                if(resolve){
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
        };
    }

    /** Steam: Update lobby visibility.*/
    default void updateLobby(){}

    /** Steam: Show multiplayer friend invite dialog.*/
    default void inviteFriends(){}

    /** Steam: Share a map on the workshop.*/
    default void publish(Publishable pub){}

    /** Steam: View a listing on the workshop.*/
    default void viewListing(Publishable pub){}

    /** Steam: View a listing on the workshop by an ID.*/
    default void viewListingID(String mapid){}

    /** Steam: Return external workshop maps to be loaded.*/
    default Seq<Fi> getWorkshopContent(Class<? extends Publishable> type){
        return new Seq<>(0);
    }

    /** Steam: Open workshop for maps.*/
    default void openWorkshop(){}

    /** Get the networking implementation.*/
    default NetProvider getNet(){
        return new ArcNetProvider();
    }

    /** Gets the scripting implementation. */
    default Scripts createScripts(){
        return new Scripts();
    }

    default Context getScriptContext(){
        Context c = Context.enter();
        c.setOptimizationLevel(9);
        return c;
    }

    /** Update discord RPC. */
    default void updateRPC(){
    }

    default void stopDiscord(){
    }

    default void startDiscord(){
    }

    default void toggleDiscord(boolean toggle){
        if (toggle) {
            startDiscord();
        } else {
            stopDiscord();
        }
    }

    /** Must be a base64 string 8 bytes in length. */
    default String getUUID(){
        String uuid = Core.settings.getString("uuid", "");
        if(uuid.isEmpty()){
            byte[] result = new byte[8];
            new Rand().nextBytes(result);
            uuid = new String(Base64Coder.encode(result));
            Core.settings.put("uuid", uuid);
            return uuid;
        }
        return uuid;
    }

    /** Only used for iOS or android: open the share menu for a map or save. */
    default void shareFile(Fi file){
    }

    default void export(String name, String extension, FileWriter writer){
        if(!ios){
            platform.showFileChooser(false, extension, file -> {
                ui.loadAnd(() -> {
                    try{
                        writer.write(file);
                    }catch(Throwable e){
                        ui.showException(e);
                        Log.err(e);
                    }
                });
            });
        }else{
            ui.loadAnd(() -> {
                try{
                    Fi result = Core.files.local(name + "." + extension);
                    writer.write(result);
                    platform.shareFile(result);
                }catch(Throwable e){
                    ui.showException(e);
                    Log.err(e);
                }
            });
        }
    }

    /**
     * Show a file chooser.
     * @param cons Selection listener
     * @param open Whether to open or save files
     * @param extension File extension to filter
     * @param title The title of the native dialog
     */
    default void showFileChooser(boolean open, String title, String extension, Cons<Fi> cons){
        new FileChooser(title, file -> file.extEquals(extension), open, file -> {
            if(!open){
                cons.get(file.parent().child(file.nameWithoutExtension() + "." + extension));
            }else{
                cons.get(file);
            }
        }).show();
    }

    default void showFileChooser(boolean open, String extension, Cons<Fi> cons){
        showFileChooser(open, open ? "@open": "@save", extension, cons);
    }

    /**
     * Show a file chooser for multiple file types.
     * @param cons Selection listener
     * @param extensions File extensions to filter
     */
    default void showMultiFileChooser(Cons<Fi> cons, String... extensions){
        if(mobile){
            showFileChooser(true, extensions[0], cons);
        }else{
            new FileChooser("@open", file -> Structs.contains(extensions, file.extension().toLowerCase()), true, cons).show();
        }
    }

    /** Hide the app. Android only. */
    default void hide(){
    }

    /** Forces the app into landscape mode.*/
    default void beginForceLandscape(){
    }

    /** Stops forcing the app into landscape orientation.*/
    default void endForceLandscape(){
    }

    interface FileWriter{
        void write(Fi file) throws Throwable;
    }
}
