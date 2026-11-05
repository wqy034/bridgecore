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

class MekmonBridgeScriptTest {
    static class Harness {
        final CCDisplayPeripheralTest.Rig rig = new CCDisplayPeripheralTest.Rig();
        final LuaState state = new LuaState();
        int acquisitions;
        Harness(int width, int height) throws Exception {
            rig.sink.resize(width,height);rig.p.surfaceChanged();
            CoreLibraries.debugGlobals(state);
            LuaTable display=new LuaTable();
            for(String method:rig.p.getMethodNames()) display.rawset(method,LibFunction.createV((s,args)->{
                if(method.equals("acquire")) acquisitions++;
                Object[] values=new Object[args.count()];
                for(int i=0;i<values.length;i++) {
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
                } catch(LuaException e) {throw new LuaError(e.getMessage());}
            }));
            state.globals().rawset("display",display);
            state.globals().rawset("presentFrame",LibFunction.create(s->{rig.p.tick();return Constants.NIL;}));
            state.globals().rawset("reclaim",LibFunction.create(s->{rig.p.revoke("settings");return Constants.NIL;}));
            state.globals().rawset("resize",LibFunction.create((s,w,h)->{rig.sink.resize(w.toInteger(),h.toInteger());rig.p.surfaceChanged();return Constants.NIL;}));
            script("""
                polls, writes, reads = {0,0,0,0}, {}, 0
                injection=10
                local kinds={'fusionReactorLogicAdapter','boilerValve','turbineValve','inductionPort'}
                local devices={}
                for i=1,4 do
                    local id=i
                    devices['dev'..i]=setmetatable({}, {__index=function(_,method)
                        return function(...)
                            reads=reads+1
                            if method=='getMinPos' then return {x=id,y=0,z=0} end
                            if method=='getMaxPos' then return {x=id,y=1,z=1} end
                            if method=='isFormed' then polls[id]=polls[id]+1 return true end
                            if method=='joulesToFE' then return (...)/2.5 end
                            if method=='getInjectionRate' then return injection end
                            if method=='setInjectionRate' then injection=(...) writes[#writes+1]=injection return end
                            if method=='incrementDumpingMode' or method=='decrementDumpingMode' then writes[#writes+1]=method return end
                            if method=='getLogicMode' then return 'READY' end
                            if method=='getDumpingMode' then return 'IDLE' end
                            if method=='getHohlraum' then return {count=1} end
                            if method=='isActiveCooledLogic' or method=='isIgnited' then return true end
                            return 100
                        end
                    end})
                end
                devices.duplicate=devices.dev1
                local names={'left','dev1','dev2','dev3','dev4','duplicate'}
                peripheral={
                    getNames=function() return names end,
                    getType=function(name) if name=='left' then return 'bridgecore_display' end if name=='duplicate' then return kinds[1] end return kinds[tonumber(name:sub(4))] end,
                    hasType=function(name,kind) return peripheral.getType(name)==kind end,
                    wrap=function(name) if name=='left' then return display else return devices[name] end end,
                }
                textutils={formatTime=function() return '12:00' end}
                print=function() end
                os={clock=function() return 0 end,time=function() return 12 end,getComputerID=function() return 1 end}
                local timer=0
                os.startTimer=function() timer=timer+1 return timer end
                """);
        }
        void script(String code) throws Exception {
            try(var in=new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8))) {
                LuaThread.runMain(state,LoadState.load(state,in,"test",state.globals()));
            }
        }
        void run() throws Exception {
            try(var in=Files.newInputStream(Path.of("lua/mekmon_bridge.lua"))) {
                LuaThread.runMain(state,LoadState.load(state,in,"mekmon_bridge.lua",state.globals()));
            }
        }
    }
    @Test void scriptCompilesAndAllFourKindsSampleOneAtATimeWithNoReadsDuringClockOrPagePaint() throws Exception {
        try(var in=Files.newInputStream(Path.of("lua/mekmon_bridge.lua"))) {assertNotNull(LuaC.compile(new LuaState(),in,"mekmon_bridge"));}
        for(int size:new int[]{128,384,512}) {
            Harness h=new Harness(size,size);
            h.script("""
                local step, previous=0,0
                os.pullEvent=function()
                    presentFrame();step=step+1
                    if step==1 then return 'timer',1 end
                    if step==2 then assert(polls[1]==1 and polls[2]==0) previous=reads return 'timer',2 end
                    if step==3 then assert(reads==previous) return 'bridge_display_touch','wrong_screen',5,5,false end
                    if step==4 then assert(reads==previous) return 'timer',4 end
                    if step==5 then assert(polls[2]==1 and polls[3]==0) return 'timer',6 end
                    if step==6 then assert(polls[3]==1 and polls[4]==0) return 'timer',7 end
                    assert(polls[1]==1 and polls[2]==1 and polls[3]==1 and polls[4]==1)
                    return 'char','q'
                end
                """);
            h.run();assertFalse(h.rig.p.isActive());assertEquals(1,h.acquisitions);assertTrue(h.rig.sink.flushes>0);
        }
    }
    @Test void smallScreenDetailsExposeInjectionControlsWithSneakAndPagePaintDoesNotSample() throws Exception {
        Harness h=new Harness(128,128);
        h.script("""
            local step, previous=0,0
            os.pullEvent=function()
                presentFrame();step=step+1
                if step==1 then return 'timer',1 end
                if step==2 then previous=reads return 'bridge_display_touch','left',4,99,false end
                if step==3 or step==4 then assert(reads==previous) return 'bridge_display_touch','left',4,99,false end
                if step==5 then assert(reads==previous) return 'bridge_display_touch','left',76,39,false end
                if step==6 then assert(injection==8) return 'bridge_display_touch','left',76,39,true end
                if step==7 then assert(injection==0) return 'bridge_display_touch','left',103,39,false end
                if step==8 then assert(injection==2) return 'bridge_display_touch','left',103,39,true end
                assert(injection==12 and #writes==4 and polls[1]==1)
                return 'char','q'
            end
            """);
        h.run();assertFalse(h.rig.p.isActive());
    }
    @Test void guiReclaimAndResizeUseCachedDataAndDoNotReacquire() throws Exception {
        Harness h=new Harness(128,128);
        h.script("""
            local step,previous=0,0
            os.pullEvent=function()
                presentFrame();step=step+1
                if step==1 then return 'timer',1 end
                if step==2 then previous=reads resize(384,384) return 'bridge_display_resize','left',384,384 end
                assert(reads==previous)
                reclaim()
                return 'bridge_display_released','left','settings'
            end
            """);
        h.run();assertFalse(h.rig.p.isActive());assertEquals(1,h.acquisitions);
    }
    @Test void missingGuiPermissionOrAmbiguousDisplaysCannotAcquireOrReadMek() throws Exception {
        Harness h=new Harness(128,128);h.script("display.isCcEnabled=function() return false end");
        LuaError err=assertThrows(LuaError.class,h::run);assertTrue(err.getMessage().contains("CC mode"));assertEquals(0,h.acquisitions);
        Harness multiple=new Harness(128,128);
        multiple.script("peripheral.getNames=function() return {'left','right'} end;peripheral.hasType=function() return true end");
        LuaError ambiguous=assertThrows(LuaError.class,multiple::run);assertTrue(ambiguous.getMessage().contains("Multiple BridgeCore"));assertEquals(0,multiple.acquisitions);
    }

    @Test void pagedTurbineDumpControlsKeepDirectionAndTouchQuitReleases() throws Exception {
        Harness h=new Harness(128,128);
        h.script("""
            local step,previous=0,0
            os.pullEvent=function()
                presentFrame();step=step+1
                if step==1 then return 'timer',1 end
                if step==2 then return 'timer',4 end
                if step==3 then return 'timer',5 end
                if step==4 then return 'timer',6 end
                if step==5 then previous=reads return 'bridge_display_touch','left',70,113,false end
                if step==6 then assert(reads==previous) return 'bridge_display_touch','left',70,113,false end
                if step==7 or step==8 then assert(reads==previous) return 'bridge_display_touch','left',4,99,false end
                if step==9 then assert(reads==previous) return 'bridge_display_touch','left',5,50,false end
                if step==10 then assert(writes[1]=='incrementDumpingMode') return 'bridge_display_touch','left',5,50,true end
                assert(writes[2]=='decrementDumpingMode' and #writes==2)
                return 'bridge_display_touch','left',100,113,false
            end
            """);
        h.run();assertFalse(h.rig.p.isActive());assertEquals(1,h.rig.sink.resumes);
    }
    @Test void totalSurfaceLossAndUnexpectedErrorsCleanlyEndTheSession() throws Exception {
        Harness lost=new Harness(128,128);
        lost.script("os.pullEvent=function() resize(0,0) return 'bridge_display_resize','left',0,0 end");
        lost.run();assertFalse(lost.rig.p.isActive());assertEquals(1,lost.rig.sink.resumes);
        Harness failed=new Harness(128,128);
        failed.script("os.pullEvent=function() error('simulated termination') end");
        LuaError err=assertThrows(LuaError.class,failed::run);
        assertTrue(err.getMessage().contains("simulated termination"));assertFalse(failed.rig.p.isActive());assertEquals(1,failed.rig.sink.resumes);
    }

    @Test void queuedOldTouchAfterLeaseRevocationCannotWriteMekSettings() throws Exception {
        Harness h=new Harness(128,128);
        h.script("""
            local step=0
            os.pullEvent=function()
                presentFrame();step=step+1
                if step==1 then return 'timer',1 end
                if step==2 or step==3 or step==4 then return 'bridge_display_touch','left',4,99,false end
                reclaim()
                return 'bridge_display_touch','left',76,39,false
            end
            """);
        LuaError err=assertThrows(LuaError.class,h::run);
        assertTrue(err.getMessage().contains("Display ownership ended"));
        h.script("assert(#writes==0 and injection==10)");assertFalse(h.rig.p.isActive());
    }
}
