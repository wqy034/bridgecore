package com.bridgecore.firecontrol;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChannelTargetAllocatorTest {
    private static TargetAssigner.Candidate c(String id,double d) { return new TargetAssigner.Candidate(id,d,true); }
    private static ChannelTargetAllocator.Turret t(String id,String previous,TargetAssigner.Candidate... cs) {
        return new ChannelTargetAllocator.Turret(id,previous,List.of(cs));
    }
    @Test void twoTurretsDoNotBothConvergeWhenTwoContactsAreReachable() {
        var out=ChannelTargetAllocator.assign(List.of(t("1",null,c("a",10),c("b",20)),t("2",null,c("a",11),c("b",21))));
        assertEquals(2,out.size());assertEquals(2,new HashSet<>(out.values()).size());
    }
    @Test void soleReachableContactAllowsConvergence() {
        assertEquals(Map.of("1","a","2","a"),ChannelTargetAllocator.assign(List.of(t("1",null,c("a",10)),t("2",null,c("a",20)))));
    }
    @Test void constrainedArcDoesNotLoseItsOnlyTarget() {
        var out=ChannelTargetAllocator.assign(List.of(t("1",null,c("a",5),c("b",10)),t("2",null,c("a",9))));
        assertEquals(Map.of("1","b","2","a"),out);
    }
    @Test void heartbeatReallocationDoesNotSwapOwnersOfUnequalDistanceContacts() {
        Map<String,String> before=Map.of("1","a","2","b");
        for(int i=0;i<100;i++) {
            var out=ChannelTargetAllocator.assign(List.of(t("1",before.get("1"),c("a",10),c("b",50)),t("2",before.get("2"),c("a",11),c("b",51))));
            assertEquals(before,out);before=out;
        }
    }
    @Test void disappearingTargetReassignsAndOutOfArcNeverWins() {
        var out=ChannelTargetAllocator.assign(List.of(t("1","gone",new TargetAssigner.Candidate("bad",1,false),c("ok",20))));
        assertEquals(Map.of("1","ok"),out);
    }
    @Test void unownedMuchCloserTargetCanReplaceAStableOne() {
        assertEquals(Map.of("1","b"),ChannelTargetAllocator.assign(List.of(t("1","a",c("a",100),c("b",5)))));
    }
    @Test void everyThreeByThreeArcGraphGetsMaximumDistinctCoverage() {
        for(int graph=0;graph<512;graph++) {
            List<ChannelTargetAllocator.Turret> units=new ArrayList<>();
            for(int u=0;u<3;u++) {
                List<TargetAssigner.Candidate> cs=new ArrayList<>();
                for(int t=0;t<3;t++) if((graph&(1<<(u*3+t)))!=0) cs.add(c("t"+t,10+t));
                units.add(new ChannelTargetAllocator.Turret("u"+u,null,cs));
            }
            int expected=0;
            for(int a=-1;a<3;a++)for(int b=-1;b<3;b++)for(int c=-1;c<3;c++) {
                int[] choice={a,b,c};Set<Integer> used=new HashSet<>();boolean valid=true;
                for(int u=0;u<3;u++) if(choice[u]>=0) {
                    if((graph&(1<<(u*3+choice[u])))==0)valid=false;
                    used.add(choice[u]);
                }
                if(valid)expected=Math.max(expected,used.size());
            }
            var out=ChannelTargetAllocator.assign(units);
            assertEquals(expected,new HashSet<>(out.values()).size(),"graph="+graph);
        }
    }

}
