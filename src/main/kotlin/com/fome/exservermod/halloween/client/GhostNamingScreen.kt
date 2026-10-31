package com.fome.exservermod.halloween.client

import com.fome.exservermod.halloween.network.SubmitGhostNamePayload
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

class GhostNamingScreen : Screen(Component.translatable("exservermod.halloween.ghost_naming_title")) {
    private lateinit var nameBox: EditBox
    private lateinit var confirmButton: Button

    override fun init() {
        super.init()

        val centerX = width / 2
        val centerY = height / 2

        nameBox =
            EditBox(
                font,
                centerX - 100,
                centerY - 10,
                200,
                20,
                Component.translatable("exservermod.halloween.ghost_naming_prompt"),
            )
        nameBox.setMaxLength(24)
        nameBox.value = "Каспер"
        addRenderableWidget(nameBox)

        confirmButton =
            Button
                .builder(Component.translatable("exservermod.halloween.ghost_naming_confirm")) {
                    submitName()
                }.bounds(centerX - 50, centerY + 20, 100, 20)
                .build()

        addRenderableWidget(confirmButton)
        setInitialFocus(nameBox)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            submitName()
            return true
        }
        return super.keyPressed(event)
    }

    private fun submitName() {
        val name = nameBox.value.trim().ifEmpty { "Призрак" }
        ClientPlayNetworking.send(SubmitGhostNamePayload(name))
        onClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        extractTransparentBackground(extractor)

        val cx = width / 2
        val cy = height / 2

        val pW = 230
        val pH = 110
        val pLeft = cx - pW / 2
        val pTop = cy - 55

        extractor.fill(pLeft - 2, pTop - 2, pLeft + pW + 2, pTop + pH + 2, 0x44FFA500.toInt())
        extractor.fill(pLeft, pTop, pLeft + pW, pTop + pH, 0xEE141218.toInt())
        extractor.outline(pLeft, pTop, pW, pH, 0xFFFFA500.toInt())
        extractor.outline(pLeft + 2, pTop + 2, pW - 4, pH - 4, 0xFF5C3317.toInt())

        val title = Component.translatable("exservermod.halloween.ghost_naming_title")
        extractor.centeredText(font, title, cx, cy - 40, 0xFFFFA500.toInt())

        val prompt = Component.translatable("exservermod.halloween.ghost_naming_prompt")
        extractor.centeredText(font, prompt, cx, cy - 25, 0xFFE0E0E0.toInt())

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false
}
