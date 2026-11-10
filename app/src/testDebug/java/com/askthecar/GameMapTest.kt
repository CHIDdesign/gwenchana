package com.askthecar

import org.junit.Assert.assertEquals
import org.junit.Test

class GameMapTest {
    @Test fun smootherFollowsMotionAndJumpsOnReset() {
        val s = PoseSmoother()
        s.at(0.0, GamePose(0.0, 0.0, 350.0))
        s.at(0.05, GamePose(1.0, 0.0, 10.0)) // 초속 20m, 머리 방향은 350→10 으로 짧은 쪽
        val mid = s.at(0.1, GameState.pose ?: GamePose(1.0, 0.0, 10.0))!!
        assertEquals(true, mid.x in 0.0..3.0)
        val jump = s.at(0.15, GamePose(500.0, 0.0, 0.0))!! // 리셋·순간이동은 끌지 않고 바로
        assertEquals(500.0, jump.x, 0.001)
    }

    @Test fun roadIndexFindsLongRoadsThatPassThroughView() {
        // 끝점이 둘 다 화면(반경 50m) 밖이어도 가운데를 지나는 1km 도로는 보여야 함
        val roads = GameMap.Roads(listOf(GameMap.Node(-500.0, 0.0, 4.0), GameMap.Node(500.0, 0.0, 4.0), GameMap.Node(0.0, 900.0, 4.0)),
            listOf(GameMap.Edge(0, 1, 13.0), GameMap.Edge(1, 2, 13.0)))
        assertEquals(listOf(0), roads.near(0.0, 0.0, 50.0).toList())
        assertEquals(emptyList<Int>(), roads.near(-800.0, 800.0, 50.0).toList())
    }
}
