package com.gaius.taoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextPolicyTest {

    @Test
    fun commonTaobaoActionsUseShortFrenchLabels() {
        assertEquals("Connexion", TranslationTextPolicy.localTranslation("【登录】"))
        assertEquals("Accepter les conditions et quitter le mode navigation", TranslationTextPolicy.localTranslation(
            "同意协议并退出浏览模式"
        ))
        assertEquals("Ajouter au panier", TranslationTextPolicy.localTranslation("加入购物车"))
        assertEquals(
            "Rechercher produits ou boutiques",
            TranslationTextPolicy.localTranslation("寻找宝贝／店铺")
        )
    }

    @Test
    fun pricesAndTechnicalValuesAreNeverSelectedForTranslation() {
        assertFalse(TranslationTextPolicy.shouldTranslate("到手价 ￥27.2"))
        assertFalse(TranslationTextPolicy.shouldTranslate("到手价 27.2 起"))
        assertFalse(TranslationTextPolicy.shouldTranslate("商品 https://item.taobao.com/123"))
        assertFalse(TranslationTextPolicy.shouldTranslate("商品 ABC12345678901234"))
        assertTrue(TranslationTextPolicy.shouldTranslate("商品详情"))
    }

    @Test
    fun machineTranslationMustKeepEveryNumber() {
        assertEquals(
            "Huile de sésame 500 ml",
            TranslationTextPolicy.cleanTranslation(
                "纯芝麻油500ml",
                "huile de sésame 500 ml"
            )
        )
        assertNull(
            TranslationTextPolicy.cleanTranslation(
                "纯芝麻油500ml",
                "huile de sésame"
            )
        )
        assertNull(
            TranslationTextPolicy.cleanTranslation(
                "进口903LU接头",
                "Raccord importé"
            )
        )
        assertEquals(
            "Raccord 903 LU importé",
            TranslationTextPolicy.cleanTranslation(
                "进口903LU接头",
                "raccord 903 LU importé"
            )
        )
        assertEquals(
            "Cette chemise est en coton",
            TranslationTextPolicy.cleanTranslation(
                "这件衬衫是纯棉的",
                "Cette chemise est coton"
            )
        )
    }

    @Test
    fun shortActionsUseInlinePresentation() {
        assertTrue(
            TranslationTextPolicy.shouldUseInlinePresentation("登录", "Connexion")
        )
        assertFalse(
            TranslationTextPolicy.shouldUseInlinePresentation(
                "亲部分浏览功能在当前模式下受到限制",
                "Certaines fonctions de navigation sont limitées dans ce mode"
            )
        )
    }
}
