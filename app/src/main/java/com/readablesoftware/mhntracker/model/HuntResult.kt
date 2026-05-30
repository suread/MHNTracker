package com.readablesoftware.mhntracker.model

data class HuntResult(
    val monsterNameRaw: String,
    val starCount: Int,
    val starColour: String,
    val drops: List<MaterialDrop>,
)