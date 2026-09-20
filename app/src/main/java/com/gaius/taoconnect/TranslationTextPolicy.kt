package com.gaius.taoconnect

/**
 * Règles de texte indépendantes d'Android.
 *
 * Elles isolent les décisions linguistiques du navigateur et empêchent une
 * traduction automatique approximative de modifier un prix, une référence ou
 * un nombre important. Les libellés Taobao les plus courants sont volontairement
 * courts afin de tenir dans les boutons d'origine.
 */
internal object TranslationTextPolicy {

    private val chineseRegex = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")
    private val whitespaceRegex = Regex("\\s+")
    private val canonicalPunctuationRegex = Regex(
        "[\\s·•|｜/／:：,，。.!！?？\\\"'“”‘’（）()\\[\\]【】<>《》]"
    )
    private val numberRegex = Regex("[+-]?\\d+(?:[.,]\\d+)?(?:\\s*[%％])?")
    private val mixedIdentifierRegex = Regex(
        "(?i)(?<![a-z0-9_-])(?=[a-z0-9_-]{2,}(?![a-z0-9_-]))" +
            "(?=[a-z0-9_-]*[a-z])(?=[a-z0-9_-]*\\d)[a-z0-9_-]+" +
            "(?![a-z0-9_-])"
    )
    private val urlOrEmailRegex = Regex(
        "(?i)(https?://|www\\.|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})"
    )
    private val currencyRegex = Regex(
        "(?i)([¥￥$€£]\\s*\\d|\\d(?:[\\d.,\\s]*\\d)?\\s*(元|人民币|rmb|cny|eur|usd))"
    )
    private val priceKeywordRegex = Regex(
        "((价格|售价|原价|到手价|优惠价|券后价|券后|促销价|现价)[^\\d]{0,8}\\d|" +
            "\\d[^\\d]{0,8}(元|人民币))"
    )
    private val technicalReferenceRegex = Regex("(?i)[a-z0-9_-]{14,}")

    private val conciseGlossary = mapOf(
        "登录" to "Connexion",
        "立即登录" to "Se connecter",
        "请登录" to "Se connecter",
        "登录淘宝" to "Connexion Taobao",
        "注册" to "S’inscrire",
        "免费注册" to "S’inscrire",
        "搜索" to "Rechercher",
        "搜索商品" to "Rechercher un produit",
        "搜索店铺" to "Rechercher une boutique",
        "寻找宝贝/店铺" to "Rechercher produits ou boutiques",
        "搜索宝贝/店铺" to "Rechercher produits ou boutiques",
        "找宝贝/店铺" to "Rechercher produits ou boutiques",
        "确认" to "Confirmer",
        "确定" to "Confirmer",
        "同意" to "Accepter",
        "继续" to "Continuer",
        "同意并继续" to "Accepter et continuer",
        "取消" to "Annuler",
        "关闭" to "Fermer",
        "完成" to "Terminer",
        "下一步" to "Suivant",
        "提交" to "Envoyer",
        "选择" to "Choisir",
        "保存" to "Enregistrer",
        "删除" to "Supprimer",
        "返回" to "Retour",
        "退出" to "Quitter",
        "浏览模式" to "Mode navigation",
        "退出浏览模式" to "Quitter le mode navigation",
        "继续浏览" to "Continuer la navigation",
        "同意协议并退出浏览模式" to "Accepter les conditions et quitter le mode navigation",
        "请输入手机号" to "Numéro de téléphone",
        "请输入手机号码" to "Numéro de téléphone",
        "请输入验证码" to "Code de vérification",
        "获取验证码" to "Recevoir le code",
        "手机号" to "Numéro de téléphone",
        "验证码" to "Code de vérification",
        "未注册的手机号验证通过后将自动注册" to
            "Après vérification, un numéro non inscrit sera inscrit automatiquement",
        "我已阅读并同意" to "J’ai lu et j’accepte",
        "服务协议" to "Conditions d’utilisation",
        "隐私权政策" to "Politique de confidentialité",
        "其他登录方式" to "Autres méthodes de connexion",
        "立即购买" to "Acheter maintenant",
        "立即抢购" to "Acheter maintenant",
        "购买" to "Acheter",
        "加入购物车" to "Ajouter au panier",
        "购物车" to "Panier",
        "去结算" to "Passer à la caisse",
        "结算" to "Passer à la caisse",
        "支付" to "Payer",
        "去付款" to "Payer",
        "确认收货" to "Confirmer la réception",
        "查看物流" to "Suivre la livraison",
        "商品详情" to "Détails du produit",
        "宝贝详情" to "Détails du produit",
        "店铺" to "Boutique",
        "进店" to "Voir la boutique",
        "客服" to "Assistance",
        "联系卖家" to "Contacter le vendeur",
        "收藏" to "Favoris",
        "分享" to "Partager",
        "销量" to "Ventes",
        "月销" to "Ventes mensuelles",
        "已售" to "Vendus",
        "券后" to "Après réduction",
        "领券" to "Obtenir le coupon",
        "包邮" to "Livraison gratuite",
        "运费" to "Frais de livraison",
        "规格" to "Variante",
        "选择规格" to "Choisir une variante",
        "选规格" to "Choisir une variante",
        "颜色分类" to "Couleur",
        "颜色" to "Couleur",
        "尺码" to "Taille",
        "数量" to "Quantité",
        "收货地址" to "Adresse de livraison",
        "地址" to "Adresse",
        "订单" to "Commandes",
        "全部订单" to "Toutes les commandes",
        "待付款" to "À payer",
        "待发货" to "À expédier",
        "待收货" to "En livraison",
        "待评价" to "À évaluer",
        "我的淘宝" to "Mon compte",
        "消息" to "Messages",
        "首页" to "Accueil",
        "推荐" to "Recommandations",
        "评价" to "Avis",
        "问大家" to "Questions",
        "已选" to "Sélectionné",
        "库存" to "Stock",
        "黑色" to "Noir",
        "白色" to "Blanc",
        "蓝色" to "Bleu",
        "红色" to "Rouge",
        "绿色" to "Vert",
        "黄色" to "Jaune",
        "灰色" to "Gris",
        "粉色" to "Rose",
        "米色" to "Beige",
        "均码" to "Taille unique",
        "纯棉" to "Coton",
        "材质" to "Matière",
        "配送" to "Livraison",
        "发货地" to "Lieu d’expédition",
        "退货" to "Retour du produit",
        "退款" to "Remboursement",
        "优惠券" to "Coupon de réduction",
        "商品评价" to "Avis sur le produit",
        "更多" to "Voir plus",
        "七天无理由退货" to "Retour sous 7 jours sans motif"
    ).mapKeys { (source, _) -> canonical(source) }

    private val actionKeywords = listOf(
        "登录",
        "注册",
        "搜索",
        "确认",
        "确定",
        "同意",
        "继续",
        "退出",
        "取消",
        "关闭",
        "完成",
        "下一步",
        "提交",
        "选择",
        "购买",
        "加入购物车",
        "支付",
        "保存",
        "结算"
    )

    fun normalizeSource(text: String): String =
        whitespaceRegex.replace(text.trim(), " ")

    fun cacheKey(text: String): String =
        normalizeSource(text).lowercase()

    fun chineseCharacterCount(text: String): Int =
        chineseRegex.findAll(text).count()

    fun shouldTranslate(text: String): Boolean {
        val normalized = normalizeSource(text)
        if (normalized.isBlank() || chineseCharacterCount(normalized) < 2) return false
        if (urlOrEmailRegex.containsMatchIn(normalized)) return false
        if (currencyRegex.containsMatchIn(normalized)) return false
        if (priceKeywordRegex.containsMatchIn(normalized)) return false
        if (technicalReferenceRegex.containsMatchIn(normalized)) return false
        return true
    }

    fun isAction(text: String): Boolean =
        actionKeywords.any { keyword -> text.contains(keyword) }

    fun localTranslation(text: String): String? =
        conciseGlossary[canonical(text)]

    fun cleanTranslation(source: String, machineTranslation: String): String? {
        val local = localTranslation(source)
        val candidate = (local ?: machineTranslation)
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .let { whitespaceRegex.replace(it, " ") }
            .trim()

        if (candidate.isBlank()) return null
        if (chineseCharacterCount(candidate) > 0) return null
        if (!preservesNumbers(source, candidate)) return null
        if (!preservesIdentifiers(source, candidate)) return null

        return candidate.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }

    fun shouldUseInlinePresentation(source: String, translation: String): Boolean {
        val chineseCount = chineseCharacterCount(source)
        return localTranslation(source) != null ||
            (isAction(source) && chineseCount <= 14 && translation.length <= 32) ||
            (chineseCount <= 8 && translation.length <= 24)
    }

    private fun preservesNumbers(source: String, translation: String): Boolean =
        numberTokens(source) == numberTokens(translation)

    private fun preservesIdentifiers(source: String, translation: String): Boolean {
        val compactTranslation = translation
            .uppercase()
            .replace(Regex("[\\s_-]+"), "")
        return mixedIdentifierRegex.findAll(source).all { match ->
            val sourceIdentifier = match.value
                .uppercase()
                .replace(Regex("[\\s_-]+"), "")
            compactTranslation.contains(sourceIdentifier)
        }
    }

    private fun numberTokens(text: String): List<String> =
        numberRegex.findAll(text)
            .map { match ->
                match.value
                    .replace(" ", "")
                    .replace('，', ',')
                    .replace(',', '.')
                    .replace('％', '%')
            }
            .toList()

    private fun canonical(text: String): String =
        canonicalPunctuationRegex.replace(normalizeSource(text), "")
}
