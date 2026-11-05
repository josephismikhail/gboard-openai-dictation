import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Isolate the dictation client's dependencies from Gboard's private library versions. */
public final class Relocate {
    private static String rename(String name) {
        for(String prefix:new String[]{"okhttp3/","okio/","kotlin/","com/trevornk/ramblr/"})
            if(name.startsWith(prefix))return "org/gboardopenai/libraries/"+name;
        return name;
    }
    public static void main(String[] args)throws Exception {
        Set<String> seen=new HashSet<>();
        Remapper remapper=new Remapper(){
            public String map(String name){return rename(name);}
            public Object mapValue(Object value){
                if(value instanceof String){String s=(String)value;String mapped=rename(s.replace('.','/'));if(!mapped.equals(s.replace('.','/')))return s.contains("/")?mapped:mapped.replace('/','.');}
                return super.mapValue(value);
            }
        };
        try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(Paths.get(args[0])))){
            for(int i=1;i<args.length;i++)try(JarFile input=new JarFile(args[i])){
                Enumeration<JarEntry> entries=input.entries();
                while(entries.hasMoreElements()){
                    JarEntry entry=entries.nextElement();String name=entry.getName();
                    if(entry.isDirectory()||!name.endsWith(".class")||name.endsWith("module-info.class"))continue;
                    byte[] bytes=input.getInputStream(entry).readAllBytes();
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer,remapper),0);
                    name=rename(name);if(!seen.add(name))throw new IllegalStateException("Duplicate class: "+name);
                    output.putNextEntry(new JarEntry(name));output.write(writer.toByteArray());output.closeEntry();
                }
            }
        }
        System.out.println("Isolated "+seen.size()+" classes");
    }
}
