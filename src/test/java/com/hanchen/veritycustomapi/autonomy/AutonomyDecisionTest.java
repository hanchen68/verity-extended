package com.hanchen.veritycustomapi.autonomy;

public final class AutonomyDecisionTest {
    public static void main(String[] args) {
        require(AutonomyDecision.parse("{\"action\":\"KEEP\",\"reason\":\"等待结果\"}").action().equals("KEEP"));
        require(AutonomyDecision.parse("```json\n{\"action\":\"SCOUT\",\"candidate\":\"p2\"}\n```").candidate().equals("p2"));
        reject("{\"action\":\"COMMAND\",\"tool\":\"op\"}");
        reject("{\"action\":\"SCOUT\",\"candidate\":\"100,64,100\"}");
        reject("{\"action\":\"TOOL\",\"tool\":\"destroy_house\"}");
        reject("{\"action\":\"KEEP\",\"x\":1e999}");
        reject("{\"action\":12}");
        reject("{\"action\":\"TOOL\",\"tool\":\"harvest_area\",\"args\":[]}");
        reject("执行成功！{\"action\":\"KEEP\"}");
        reject("{\"action\":\"KEEP\",\"reason\":\"" + "x".repeat(241) + "\"}");
        for (int mask = 0; mask < 8; mask++) {
            boolean enabled = (mask & 1) != 0;
            boolean suspended = (mask & 2) != 0;
            boolean tools = (mask & 4) != 0;
            require(AutonomyPermissions.canEdit(enabled, suspended, tools) == (mask == 5));
        }
        // No grant token, timer or per-action instruction is required in standing-consent mode.
        require(AutonomyPermissions.canEdit(true, false, true));
        require(AutonomyPermissions.canEdit(true, false, true));
        var multi = AutonomyDecision.parse("{\"goal\":\"考察附近\",\"completion\":\"读到实际环境\",\"steps\":[{\"action\":\"TOOL\",\"tool\":\"scan_area\",\"args\":{}},{\"action\":\"SCOUT\",\"candidate\":\"p1\"}],\"speech\":\"我去附近看看。\"}");
        require(multi.steps().size() == 2);
        require(multi.goal().equals("考察附近"));
        var copy = multi.steps(); copy.remove(0); require(multi.steps().size() == 2);
        reject("{\"steps\":[{\"action\":\"TOOL\",\"tool\":\"scan_area\",\"command\":\"op\"}]}");
        reject("{\"steps\":[{\"action\":\"KEEP\"}]}");
        reject("{\"goalState\":\"SUCCESS\"}");
        reject("{\"speech\":\"" + "x".repeat(181) + "\"}");
        reject("{\"steps\":[{\"action\":\"SCOUT\",\"candidate\":\"p1\",\"x\":123}]}");
        require(AutonomyEvidence.knownFailure("半径 8 格内没找到矿石。"));
        require(AutonomyEvidence.knownFailure("这里不是一个容器。"));
        require(AutonomyEvidence.knownFailure("最终结果未验证"));
        require(!AutonomyEvidence.knownFailure("实际读取到：minecraft:oak_log × 4"));
        require(!AutonomyEvidence.mayReview(0,0,false,true));
        require(!AutonomyEvidence.mayReview(2,1,false,true));
        require(!AutonomyEvidence.mayReview(2,0,true,true));
        require(!AutonomyEvidence.mayReview(2,0,false,false));
        require(AutonomyEvidence.mayReview(2,0,false,true));
        require(!AutonomyPermissions.commandedWait(false, 0, false));
        require(!AutonomyPermissions.commandedWait(false, -1, false));
        require(AutonomyPermissions.commandedWait(false, 20, false));
        require(AutonomyPermissions.commandedWait(true, 0, false));
        require(AutonomyPermissions.commandedWait(false, 0, true));
        require(AutonomyDecision.parse("{\"action\":\"KEEP\",\"candidate\":null,\"tool\":null,\"args\":null,\"speech\":null}").args().size()==0);
        require(AutonomyDecision.parse("{\"steps\":[{\"action\":\"TOOL\",\"tool\":\"scan_area\",\"args\":null}]}").steps().size()==1);
        require(AutonomyDecision.parse("{\"useSkill\":\"chop_tree\"}").useSkill().equals("chop_tree"));
        require(AutonomyDecision.parse("{\"skillName\":\"mine_clay\"}").skillName().equals("mine_clay"));
        reject("{\"useSkill\":\"Chop Tree\"}");
        reject("{\"skillName\":\"../etc/passwd\"}");
        reject("{\"useSkill\":\"x\"}");
        require(AutonomySkills.validName("chop_tree"));
        require(!AutonomySkills.validName("Chop_Tree"));
        require(!AutonomySkills.validName("ab"));
        require(!AutonomySkills.validName("chop-tree"));
        require(!AutonomySkills.validName(null));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"break_block\",\"args\":{\"x\":1,\"y\":64,\"z\":2}}")
                .primitive().equals("break_block"));
        require(AutonomyDecision.parse("{\"steps\":[{\"action\":\"PRIMITIVE\",\"primitive\":\"pickup\"}]}").steps().size()==1);
        reject("{\"action\":\"PRIMITIVE\",\"primitive\":\"execute_command\"}");
        reject("{\"action\":\"PRIMITIVE\",\"primitive\":\"fill_area\"}");
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"craft\",\"args\":{\"item\":\"wooden_pickaxe\"}}")
                .primitive().equals("craft"));
        reject("{\"action\":\"PRIMITIVE\",\"primitive\":\"teleport\"}");
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"fell_tree\",\"args\":{\"x\":0,\"y\":64,\"z\":0}}")
                .primitive().equals("fell_tree"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"build\",\"args\":{\"blocks\":[{\"x\":1,\"y\":64,\"z\":2,\"item\":\"minecraft:cobblestone\"}]}}")
                .primitive().equals("build"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"give\",\"args\":{\"item\":\"minecraft:oak_log\",\"count\":4}}")
                .primitive().equals("give"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"shelter\",\"args\":{\"material\":\"minecraft:cobblestone\"}}")
                .primitive().equals("shelter"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"attack\"}").primitive().equals("attack"));
        var corrected = AutonomyDecision.parse("{\"action\":\"TOOL\",\"tool\":\"mine\",\"args\":{\"x\":1,\"y\":2,\"z\":3}}");
        require(corrected.action().equals("PRIMITIVE") && corrected.primitive().equals("break_block"));
        reject("{\"action\":\"TOOL\",\"tool\":\"explode\"}");
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"tool\":\"chop\"}").primitive().equals("fell_tree"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"equip\",\"args\":{\"item\":\"minecraft:stone_pickaxe\",\"slot\":\"mainhand\"}}")
                .primitive().equals("equip"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"smelt\",\"args\":{\"item\":\"minecraft:raw_iron\",\"count\":4}}")
                .primitive().equals("smelt"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"blueprint\",\"args\":{\"structure\":\"minecraft:igloo/top\"}}")
                .primitive().equals("blueprint"));
        require(AutonomyDecision.parse("{\"action\":\"TOOL\",\"tool\":\"smelt\"}").primitive().equals("smelt"));
        require(AutonomyDecision.parse("{\"action\":\"PRIMITIVE\",\"primitive\":\"eat\"}").primitive().equals("eat"));
        require(AutonomyDecision.parse("{\"action\":\"TOOL\",\"tool\":\"eat\"}").primitive().equals("eat"));
        require(AutonomyStall.limit("MOVE") == 1200L);
        require(AutonomyStall.limit("MINE") == 2400L);
        require(AutonomyStall.limit("BLUEPRINT") == 30000L);
        require(AutonomyStall.limit(null) == 1200L);
        require(!AutonomyStall.expired("MOVE", 1100L));
        require(AutonomyStall.expired("MOVE", 1300L));
        require(!AutonomyStall.expired("BLUEPRINT", 29000L));
        require(AutonomyStall.expired("BLUEPRINT", 31000L));
        require(AutonomyDecision.parse("{\"action\":\"TOOL\"}").action().equals("KEEP"));
        var byCoords = AutonomyDecision.parse("{\"action\":\"TOOL\",\"args\":{\"x\":1,\"y\":2,\"z\":3}}");
        require(byCoords.action().equals("PRIMITIVE") && byCoords.primitive().equals("break_block"));
        require(com.hanchen.veritycustomapi.body.VerityMining.expectedTicks(2.0F, 1.0F) == 60);
        require(com.hanchen.veritycustomapi.body.VerityMining.expectedTicks(1.5F, 2.0F) == 23);
        require(com.hanchen.veritycustomapi.body.VerityMining.expectedTicks(0.0F, 1.0F) == 1);
        require(com.hanchen.veritycustomapi.body.VerityMining.expectedTicks(50.0F, 1.0F) == 600);
        System.out.println("Autonomy protocol/.../empty-tool/mining-time checks passed (86 checks)");
    }
    private static void reject(String input) {
        try { AutonomyDecision.parse(input); } catch (RuntimeException expected) { return; }
        throw new AssertionError("accepted unsafe decision: " + input);
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
}
