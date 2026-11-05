package com.bridgecore.cc;

import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.lua.LuaException;
import org.junit.jupiter.api.Test;
import org.squiddev.cobalt.*;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.compiler.LuaC;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.lib.CoreLibraries;
import java.nio.file.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.squiddev.cobalt.ValueFactory.*;
import static org.junit.jupiter.api.Assertions.*;

class LuaDisplayExamplesTest {
 @Test void ownApiExamplesCompileInTheInstalledLuaEngine() throws Exception {
  for (String name : new String[]{"bridge_display_cells.lua", "bridge_display_pixels.lua"})
   try (var in = Files.newInputStream(Path.of("lua", name))) { assertNotNull(LuaC.compile(new LuaState(), in, name)); }
 }
 @Test void ownApiExamplesPaintAndReleaseUsingOnlyTheirExportedFunctions() throws Exception {
  for (String name : new String[]{"bridge_display_cells.lua", "bridge_display_pixels.lua"}) {
   var rig = new CCDisplayPeripheralTest.Rig();
   LuaState state = new LuaState(); CoreLibraries.debugGlobals(state);
   LuaTable screen = new LuaTable();
   for (String method : rig.p.getMethodNames()) screen.rawset(method, LibFunction.createV((s,args) -> {
    Object[] values = new Object[args.count()];
    for (int i=0;i<values.length;i++) {
     LuaValue v=args.arg(i+1);
     values[i]=v.isNil()?null:v.type()==Constants.TBOOLEAN?v.toBoolean():v.type()==Constants.TNUMBER?v.toDouble():v.toString();
    }
    try {
     Object[] result=rig.p.invoke(rig.a.access,method,new ObjectArguments(values));
     LuaValue[] out=new LuaValue[result.length];
     for(int i=0;i<out.length;i++) {
      Object v=result[i];out[i]=v==null?Constants.NIL:v instanceof Number n?valueOf(n.doubleValue()):v instanceof Boolean b?valueOf(b):valueOf(v.toString());
     }
     return varargsOf(out);
    } catch(LuaException e) { throw new LuaError(e.getMessage()); }
   }));
   state.globals().rawset("display",screen);
   state.globals().rawset("presentFrame",LibFunction.create(s -> {rig.p.tick();return Constants.NIL;}));
   String setup="peripheral={find=function() return display end,getName=function() return 'left' end}; os={pullEvent=function() presentFrame() return 'char','q' end}";
   LuaThread.runMain(state,LoadState.load(state,new ByteArrayInputStream(setup.getBytes(StandardCharsets.UTF_8)),"setup",state.globals()));
   try(var in=Files.newInputStream(Path.of("lua",name))) {LuaThread.runMain(state,LoadState.load(state,in,name,state.globals()));}
   assertFalse(rig.p.isActive(),name); assertEquals(1,rig.sink.resumes,name);assertEquals(1,rig.sink.flushes,name);
  }
 }
}
