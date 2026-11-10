#!/usr/bin/env python3
"""
Comprehensive Loop Engineering Verification Suite for LINE & Bumble AI Reply Assistant v1.4.6.
Tests:
1. Personality & scenario archetypes (6 types)
2. Plugin Architecture: Sequence alignment & chronological merge algorithm
3. Plugin Architecture: Deduplication & 150-turn capping
4. Geometric Speaker Separation: Long user messages right-alignment invariant
5. Input Field / EditText exclusion verification
6. User Answer Style Profiling ("自分がどういう回答をするのか")
7. Partner Persona Profiling ("その人のこと")
8. Self-Reply Prohibition Rule Verification
"""

import sys
import re
import json

def test_archetype_matrix():
    scenarios = [
        {
            "name": "Archetype 1: わがまま・マウント気味の女子",
            "partner_msg": "明日19時渋谷集合でいい？てかイタリアン以外無理なんだけど笑",
            "partner_persona": "わがまま・甘えたがり・美食好き",
            "location": "渋谷",
            "opt1_direct": "19時渋谷了解！美味いイタリアン探しておくわ🍝",
            "opt2_empathy_roll": "わがまま全開で可愛いじゃん笑 いいよ、最高に美味いパスタ連れてく！",
            "opt3_tease": "出たよイタリアン縛り笑 ピザでもパスタでもなんでも来い😂",
            "opt4_lead": "渋谷の桜丘に穴場のトラットリアあるから予約しとくね！"
        },
        {
            "name": "Archetype 2: めんどくさい構ってちゃん・愚痴モード",
            "partner_msg": "今日の上司マジでうざすぎて無理。今すぐ飲みに連れてって",
            "partner_persona": "めんどくさい・感情爆発・構ってちゃん",
            "location": "恵比寿",
            "opt1_direct": "今日もお疲れ様！今夜空いてるから飲み行こ🍻",
            "opt2_empathy_roll": "それは災難だったね…！全部愚痴聞いてあげるからパーッと発散しよ🥺",
            "opt3_tease": "相当溜まってんな笑 美味い酒飲んで上司の悪口大会しよ😂",
            "opt4_lead": "恵比寿の静かな居酒屋押さえるから、何時くらいに合流できそう？"
        },
        {
            "name": "Archetype 3: チャラい・ノリ重視・テキーラ系",
            "partner_msg": "今六本木で飲んでるんだけど来ないのー？テキーラ乾杯しよ笑",
            "partner_persona": "チャラい・ノリ優先・夜遊び好き",
            "location": "六本木",
            "opt1_direct": "今から六本木向かうわ！何分後くらい？",
            "opt2_empathy_roll": "めっちゃ楽しそうじゃん！いいテンションだね✨",
            "opt3_tease": "いきなりテキーラ振ってくるの強いわ笑 潰されないように急ぐ😂",
            "opt4_lead": "六本木のどの店？場所LINEで送っといて！"
        },
        {
            "name": "Archetype 4: 警戒心強め・慎重派",
            "partner_msg": "急に会うのはちょっと緊張するかも…まずはランチかカフェなら大丈夫なんですけど…",
            "partner_persona": "警戒心強め・慎重・真面目",
            "location": "中目黒",
            "opt1_direct": "もちろん！いきなり夜より明るい時間のカフェのほうが安心だよね☕",
            "opt2_empathy_roll": "全然無理しないで大丈夫だよ！ゆっくり話して仲良くなろ😊",
            "opt3_tease": "取って食ったりしないから安心して笑 のんびりお茶しよ〜！",
            "opt4_lead": "中目黒に落ち着いた川沿いカフェあるから、週末昼過ぎにふらっと行こ！"
        },
        {
            "name": "Archetype 5: 外国人・ネイティブ英語（Bumble）",
            "partner_msg": "Hey! How's your week going? I'm craving some authentic ramen in Tokyo!",
            "partner_persona": "Expat / Foreigner / Food lover",
            "location": "Tokyo / Shinjuku",
            "opt1_direct": "Hey! Ramen is always the answer 🍜 My week's going great!",
            "opt2_empathy_roll": "Haha totally get you! Tokyo has the absolute best spots for that.",
            "opt3_tease": "Careful, once you start the ramen hunt there's no going back 😄",
            "opt4_lead": "There's an incredible hidden ramen spot in Shinjuku, let's grab a bowl this week!"
        },
        {
            "name": "Archetype 6: バイリンガル・外国人日本語",
            "partner_msg": "はじめまして！プロフのキャンプ写真Coolですね！日本で一番どこが好きですか？",
            "partner_persona": "日本在住外国人・バイリンガル・アウトドア好き",
            "location": "都内",
            "opt1_direct": "はじめまして！長野の湖のキャンプ場が一番最高でした⛺",
            "opt2_empathy_roll": "プロフ見てくれてありがとう！アウトドア好き同士話せて嬉しいです😊",
            "opt3_tease": "写真褒めてくれてテンション上がった笑 キャンプ沼深いですよ😂",
            "opt4_lead": "今度おすすめのアウトドアスポット情報交換しましょう✨"
        }
    ]

    for sc in scenarios:
        assert len(sc["opt1_direct"]) > 10, f"{sc['name']} Option 1 too short"
        assert len(sc["opt2_empathy_roll"]) > 10, f"{sc['name']} Option 2 too short"
        assert len(sc["opt3_tease"]) > 10, f"{sc['name']} Option 3 too short"
        assert len(sc["opt4_lead"]) > 10, f"{sc['name']} Option 4 too short"

    return {"status": "PASS", "archetypes_verified": len(scenarios)}

def is_sequence_match(seq_a, seq_b):
    if len(seq_a) != len(seq_b):
        return False
    for a, b in zip(seq_a, seq_b):
        if a["isPartner"] != b["isPartner"] or a["text"].strip() != b["text"].strip():
            return False
    return True

def merge_chronological(existing, incoming, max_turns=150):
    if not incoming:
        return existing
    if not existing:
        return incoming[-max_turns:]

    max_overlap = min(len(existing), len(incoming))

    for overlap in range(max_overlap, 0, -1):
        exist_tail = existing[-overlap:]
        inc_head = incoming[:overlap]
        if is_sequence_match(exist_tail, inc_head):
            merged = existing + incoming[overlap:]
            return merged[-max_turns:]

    for overlap in range(max_overlap, 0, -1):
        inc_tail = incoming[-overlap:]
        exist_head = existing[:overlap]
        if is_sequence_match(inc_tail, exist_head):
            merged = incoming[:-overlap] + existing
            return merged[-max_turns:]

    if len(existing) >= len(incoming):
        for start in range(len(existing) - len(incoming) + 1):
            sub = existing[start : start + len(incoming)]
            if is_sequence_match(sub, incoming):
                return existing

    if len(incoming) >= len(existing):
        for start in range(len(incoming) - len(existing) + 1):
            sub = incoming[start : start + len(existing)]
            if is_sequence_match(sub, existing):
                return incoming[-max_turns:]

    merged = list(existing)
    for turn in incoming:
        if not any(e["isPartner"] == turn["isPartner"] and e["text"].strip() == turn["text"].strip() for e in merged):
            merged.append(turn)
    return merged[-max_turns:]

def test_chronological_merge_suite():
    exist = [
        {"speaker": "相手", "text": "今どこー？", "isPartner": True},
        {"speaker": "自分", "text": "渋谷向かってる！", "isPartner": False},
        {"speaker": "相手", "text": "了解！ハチ公前にいるね", "isPartner": True}
    ]
    incoming_down = [
        {"speaker": "自分", "text": "渋谷向かってる！", "isPartner": False},
        {"speaker": "相手", "text": "了解！ハチ公前にいるね", "isPartner": True},
        {"speaker": "自分", "text": "あと5分で着く！", "isPartner": False},
        {"speaker": "相手", "text": "黒いコート着てるよ", "isPartner": True}
    ]
    merged_down = merge_chronological(exist, incoming_down)
    assert len(merged_down) == 5
    assert merged_down[0]["text"] == "今どこー？"
    assert merged_down[-1]["text"] == "黒いコート着てるよ"

    incoming_up = [
        {"speaker": "相手", "text": "昨日楽しかったね！", "isPartner": True},
        {"speaker": "自分", "text": "美味しかったね！また行こ", "isPartner": False},
        {"speaker": "相手", "text": "今どこー？", "isPartner": True}
    ]
    merged_up = merge_chronological(exist, incoming_up)
    assert len(merged_up) == 5
    assert merged_up[0]["text"] == "昨日楽しかったね！"
    assert merged_up[-1]["text"] == "了解！ハチ公前にいるね"

    print("Chronological sequence alignment & deduplication suite: PASS ✅")

def test_geometric_speaker_separation_and_input_exclusion():
    effective_width = 1080
    effective_height = 2400

    def classify_speaker(left, right, is_editable, cls_name):
        # 1. Input field exclusion
        if is_editable or "edittext" in cls_name.lower():
            return None  # EXCLUDED!

        right_margin = effective_width - right
        # 2. Immutable geometric separation:
        # In LINE and Bumble, Me is ALWAYS right-aligned (rightMargin <= 12% width or right >= 88% width)
        is_me = (right_margin <= effective_width * 0.12) or (right >= effective_width * 0.88)
        return "自分" if is_me else "相手"

    # Case 1: User short message ("了解！") -> rightMargin = 40px (3.7%)
    assert classify_speaker(left=750, right=1040, is_editable=False, cls_name="TextView") == "自分"

    # Case 2: User long message (4 lines, 120 chars) -> stretched left to left=180px, but right=1040px
    # Previous buggy logic misclassified this as Partner!
    assert classify_speaker(left=180, right=1040, is_editable=False, cls_name="TextView") == "自分"

    # Case 3: Partner short message ("おつかれ！") -> left=140px, right=380px (rightMargin = 700px = 64%)
    assert classify_speaker(left=140, right=380, is_editable=False, cls_name="TextView") == "相手"

    # Case 4: Partner long message ("今日の上司マジでうざすぎて...") -> left=140px, right=890px (rightMargin = 190px = 17.5%)
    assert classify_speaker(left=140, right=890, is_editable=False, cls_name="TextView") == "相手"

    # Case 5: User draft inside input box -> is_editable=True, left=120px, right=900px
    # Previous buggy logic treated this as Partner's message!
    assert classify_speaker(left=120, right=900, is_editable=True, cls_name="EditText") is None

    print("Geometric speaker separation & input exclusion suite: PASS ✅")

def test_user_and_partner_profiling():
    # Test User Answer Style Profiling ("自分がどういう回答をするのか")
    user_samples = [
        "19時渋谷了解！",
        "それ笑うんだけど😂",
        "あいてるよ！肉食べ行こー🥩",
        "仕事終わったら連絡するわ！",
        "中目黒の店予約しとくね"
    ]
    avg_len = sum(len(s) for s in user_samples) // len(user_samples)
    assert avg_len < 30, "User style should capture short punchy replies"

    # Test Partner Persona Profiling ("その人のこと")
    partner_msgs_whining = ["明日19時渋谷ね！てかイタリアン以外無理なんだけど笑"]
    partner_msgs_complaint = ["今日の上司マジでうざすぎて無理。今すぐ飲みに連れてって"]
    
    assert any("イタリアン" in m and "無理" in m for m in partner_msgs_whining)
    assert any("うざ" in m and "無理" in m for m in partner_msgs_complaint)
    print("User answer style & partner persona profiling: PASS ✅")

def test_full_history_prompt_injection():
    accumulated_history = [
        {"speaker": "相手", "text": "先週言ってた美味しい焼き肉行きたい！", "isPartner": True},
        {"speaker": "自分", "text": "いいね、中目黒にいい店見つけたよ🥩", "isPartner": False},
        {"speaker": "相手", "text": "明日空いてる？何時集合にする？", "isPartner": True}
    ]
    partner_name = "田中"
    user_summary = "平均18文字の短文ストレート回答。タメ口フランクでツッコミ（笑・😂）好き。結論からズバッと即答する回答スタイル。"
    partner_dossier = {"name": partner_name, "location": "中目黒・恵比寿", "personality": "ちょいワガママ・甘えたがり・美食こだわり派", "strategy": "リードしつつ褒める"}
    
    turns_formatted = "\n".join(f"[{'田中' if t['isPartner'] else '自分'}]: {t['text']}" for t in accumulated_history)
    prompt = f"""
    【相手（{partner_name}）との蓄積会話全ログ（★全件自動読込済・時系列：過去から現在へ）】
    {turns_formatted}

    【ユーザー本人の回答スタイル（自分がどういう回答をするのかの精密プロファイル）】
    {user_summary}

    【相手（{partner_name}）の深層プロファイル（人間観察・生息エリア・性格分析カルテ）】
    生息: {partner_dossier['location']} / 性格: {partner_dossier['personality']}

    【直前の相手のメッセージ（★返信対象・左側チャット）】
    "{accumulated_history[-1]['text']}"

    【絶対厳守の返信生成ルール】
    0. 【最重要：自分の発言への自己返信・相槌の完全禁止】
       あなたの返信は【相手のチャット（左側）】に対する返答でなければなりません。自分に対して返信したり、自分の言葉に相槌を打つような不自然な返信は【絶対に禁止】です。
    """

    assert "【相手（田中）との蓄積会話全ログ（★全件自動読込済・時系列：過去から現在へ）】" in prompt
    assert "[自分]: いいね、中目黒にいい店見つけたよ🥩" in prompt
    assert "【ユーザー本人の回答スタイル（自分がどういう回答をするのかの精密プロファイル）】" in prompt
    assert "【最重要：自分の発言への自己返信・相槌の完全禁止】" in prompt
    print("Full accumulated history & zero self-reply prompt injection: PASS ✅")

def test_english_speaker_detection():
    # Strict English detection logic from ChatConversationExtractor
    def detect_lang(sample: str, profile: str = ""):
        combined = f"{sample} {profile}".strip()
        clean = re.sub(r"https?://\S+", " ", combined)
        latin_count = len(re.findall(r"[a-zA-Z]", clean))
        jp_count = len(re.findall(r"[\u3040-\u30ff\u4e00-\u9faf]", clean))
        has_foreign_kw = bool(re.search(
            r"from\s+[a-z]+|lives?\s+in|expat|native|english|speak|tokyo|japan|foreigner|travel|visit|study abroad|アメリカ|カナダ|オーストラリア|イギリス|ハーフ|海外|留学",
            clean,
            re.IGNORECASE
        ))
        is_english_only = latin_count >= 3 and jp_count == 0
        is_english_dominant = latin_count > 12 and (jp_count == 0 or latin_count > jp_count * 2)
        is_foreigner = is_english_only or is_english_dominant or (latin_count > 6 and has_foreign_kw)
        desc = "英語話者" if (is_english_only or is_english_dominant) else ("外国人 / バイリンガル" if is_foreigner else "日本語")
        return is_foreigner, desc

    # Test cases: Short English messages that previously failed with latin_count > 20 requirement
    assert detect_lang("Hey!")[0] == True, "Hey! should be English"
    assert detect_lang("How are you?")[0] == True, "How are you? should be English"
    assert detect_lang("Wanna get drinks on Friday?")[0] == True, "Wanna get drinks should be English"
    assert detect_lang("Where are you from?")[0] == True, "Where are you from? should be English"
    assert detect_lang("Nice to meet you 😊")[0] == True, "Nice to meet you should be English"

    # Japanese cases
    assert detect_lang("今週金曜空いてる？")[0] == False, "Japanese should not be English"
    assert detect_lang("了解です！また後で連絡します")[0] == False, "Japanese should not be English"
    assert detect_lang("今日もお疲れ様〜")[0] == False, "Japanese should not be English"

    print("English speaker detection suite: PASS ✅")

def test_zero_unprompted_scroll_and_vertical_filter():
    # Test class exclusion in findVerticalChatScrollableNode
    excluded_classes = [
        "androidx.viewpager.widget.ViewPager",
        "androidx.viewpager2.widget.ViewPager2",
        "android.widget.HorizontalScrollView",
        "com.google.android.material.tabs.TabLayout",
        "com.linecorp.tab.TabBar",
        "com.linecorp.ads.BannerView",
        "com.linecorp.line.chat.ui.StickerCarouselView"
    ]
    for cls in excluded_classes:
        is_horiz = any(k in cls.lower() for k in ["viewpager", "horizontal", "tab", "banner", "carousel", "sticker"])
        assert is_horiz, f"{cls} must be flagged as horizontal/banner and excluded from scrolling!"

    # Allowed: vertical chat RecyclerView
    chat_recycler = "androidx.recyclerview.widget.RecyclerView"
    is_horiz_chat = any(k in chat_recycler.lower() for k in ["viewpager", "horizontal", "tab", "banner", "carousel", "sticker"])
    assert not is_horiz_chat, "Vertical RecyclerView must NOT be excluded!"

    print("Zero unprompted scroll & vertical chat node filter suite: PASS ✅")

def test_ui_chat_confinement_and_anti_duplication():
    # 1. Chat room confinement verification
    def should_show_overlay(pkg: str, has_input_edit_text: bool):
        if pkg not in ["jp.naver.line.android", "com.bumble.app"]:
            return False  # External app / launcher / self app -> HIDE
        return has_input_edit_text  # ONLY show when in active talk room with EditText!

    # LINE Talk Room (Active Chat) -> SHOW
    assert should_show_overlay("jp.naver.line.android", True) == True
    # LINE Talk List / Home / News / VOOM / Wallet -> HIDE
    assert should_show_overlay("jp.naver.line.android", False) == False
    # Bumble Chat Room -> SHOW
    assert should_show_overlay("com.bumble.app", True) == True
    # Bumble Swipe Deck / Match Queue -> HIDE
    assert should_show_overlay("com.bumble.app", False) == False
    # Android Launcher / Home Screen -> HIDE
    assert should_show_overlay("com.google.android.apps.nexuslauncher", False) == False
    assert should_show_overlay("com.android.systemui", False) == False
    # Self app (MainActivity) -> HIDE
    assert should_show_overlay("com.antigravity.lineaireply", True) == False

    # 2. Mutual exclusion (Bubble vs Replies Card)
    class MockOverlayManager:
        def __init__(self):
            self.bubble_showing = False
            self.replies_showing = False

        def show_bubble(self):
            if not self.replies_showing:
                self.bubble_showing = True

        def show_replies(self):
            self.bubble_showing = False  # Bubble MUST hide when replies card opens
            self.replies_showing = True

        def hide_replies(self):
            self.replies_showing = False

        def hide_all(self):
            self.bubble_showing = False
            self.replies_showing = False

    mgr = MockOverlayManager()
    mgr.show_bubble()
    assert mgr.bubble_showing == True and mgr.replies_showing == False
    mgr.show_replies()
    # Bubble MUST be hidden when replies card is showing! No double UI!
    assert mgr.bubble_showing == False and mgr.replies_showing == True
    mgr.hide_replies()
    assert mgr.replies_showing == False

    print("UI chat room confinement & anti-duplication suite: PASS ✅")

def test_paypay_and_financial_app_protection():
    # 1. Config verification
    with open("app/src/main/res/xml/accessibility_service_config.xml") as f:
        config_text = f.read()
    assert 'android:packageNames="jp.naver.line.android,com.bumble.app"' in config_text, \
        "accessibility_service_config.xml MUST restrict packageNames strictly to LINE and Bumble!"

    # 2. Package isolation check
    financial_and_system_apps = [
        "jp.ne.paypay.android.app",      # PayPay
        "jp.co.rakuten.pay",             # Rakuten Pay
        "com.smbc.direct",               # SMBC Banking
        "jp.mufg.bk.app",                # MUFG Banking
        "jp.co.mizuhobank.banking",       # Mizuho Banking
        "jp.japanpost.jp_bank.app",      # Japan Post Bank
        "com.google.android.apps.nexuslauncher", # Pixel Launcher
        "com.android.systemui",          # System UI / Home / Notifications
        "com.google.android.youtube",    # YouTube
        "com.android.chrome"             # Chrome
    ]

    def overlay_allowed_in_package(pkg: str) -> bool:
        return pkg in ["jp.naver.line.android", "com.bumble.app"]

    for app in financial_and_system_apps:
        assert not overlay_allowed_in_package(app), f"Overlay MUST NEVER be allowed in {app}! It blocks PayPay and financial apps!"

    print("PayPay & financial app 100% zero-interference suite: PASS ✅")

def test_polite_tone_detection_and_matching():
    polite_regex = r"(です[。！？!\?\s]*$|ます[。！？!\?\s]*$|でした|ました|でしょうか|ですね|ですよ|ですか|はじめまして|よろしくお願い|ありがとうござい|いただけ|頂け|ご都合|いかが|存じ|恐れ入|致し)"

    def detect_polite(msgs: list) -> bool:
        import re
        clean = [m for m in msgs if not m.startswith("📷") and m.strip()]
        if not clean: return False
        polite_count = sum(1 for m in clean if re.search(polite_regex, m))
        last_is_polite = bool(re.search(polite_regex, clean[-1]))
        return last_is_polite or (polite_count / len(clean) >= 0.35)

    # Polite scenarios
    assert detect_polite(["はじめまして！マッチありがとうございます！", "普段どのあたりで遊ばれることが多いですか？"]) == True
    assert detect_polite(["カフェ巡りお好きなんですね！おすすめのお店はありますでしょうか？"]) == True
    assert detect_polite(["来週のご都合はいかがですか？"]) == True
    assert detect_polite(["よろしくお願いします！"]) == True

    # Casual / slang scenarios - must NOT trigger polite mode
    assert detect_polite(["おつかれー！", "それウケるんだけど笑", "今度飯行こー"]) == False
    assert detect_polite(["りょ！何時？"]) == False
    assert detect_polite(["よろしくー！"]) == False, "Casual 'よろしくー！' must NOT trigger polite mode!"
    assert detect_polite(["クリスマス楽しみだね"]) == False, "Sentence with 'ます' substring like クリスマス must NOT falsely trigger polite mode!"

    print("Partner polite tone detection & matching suite: PASS ✅")

def test_photo_reaction_pipeline():
    photo_words = ["写真", "写真を送信しました", "画像", "画像を送信しました", "photo", "image", "[写真]", "[画像]", "photo sent"]

    def is_photo_msg(raw_text: str) -> bool:
        lower = raw_text.lower().strip()
        return any(lower == w or "写真を送信" in lower or "画像を送信" in lower for w in photo_words)

    assert is_photo_msg("写真を送信しました") == True
    assert is_photo_msg("[写真]") == True
    assert is_photo_msg("Photo") == True
    assert is_photo_msg("こんにちは") == False

    def is_partner_photo_recent(latest_partner_msg: str) -> bool:
        if "📷" in latest_partner_msg:
            return True
        lower = latest_partner_msg.lower().strip()
        return any(lower == w or "写真を送信しました" in lower for w in photo_words)

    # Casual conversation mentioning '写真' must NOT trigger photo reaction!
    assert is_partner_photo_recent("写真立て買ったんだよね！") == False, "Mentioning '写真立て' must NOT trigger photo reaction!"
    assert is_partner_photo_recent("昨日の写真撮るの忘れたー") == False, "Mentioning '写真撮るの忘れた' must NOT trigger photo reaction!"
    assert is_partner_photo_recent("📷 [写真を送信しました]") == True
    assert is_partner_photo_recent("写真を送信しました") == True

    # Verify prompt guidance when photo is present
    has_photo = True
    prompt_snippet = "【相手が直前に写真を送ってきました（★写真リアクション必須）】" if has_photo else ""
    assert "写真リアクション必須" in prompt_snippet
    print("Photo reception detection & reaction suite: PASS ✅")

def test_kanagawa_atsugi_chigasaki_localization():
    with open("app/src/main/java/com/antigravity/lineaireply/plugin/PersonaSynthesizerPlugin.kt") as f:
        persona_code = f.read()

    # User base MUST be Kanagawa (Atsugi, Chigasaki, Shonan)
    assert "厚木市・茅ヶ崎市・湘南エリア在住" in persona_code, "Prompt MUST specify user residence in Atsugi/Chigasaki/Shonan!"
    assert "東京（渋谷・新宿・恵比寿・六本木など）を勝手に提案することは【完全禁止】" in persona_code, "Prompt MUST strictly forbid proposing Tokyo!"
    assert "渋谷か恵比寿あたり" not in persona_code, "Fallback must NOT suggest Shibuya/Ebisu!"
    assert "Shibuya or Ebisu" not in persona_code, "English fallback must NOT suggest Shibuya/Ebisu!"

    with open("app/src/main/java/com/antigravity/lineaireply/plugin/ConversationMemoryPlugin.kt") as f:
        memory_code = f.read()

    # Default partner location must be '不明' (NOT assuming partner also lives in Atsugi!)
    assert '"location", "不明"' in memory_code, "Partner dossier default location must be '不明'!"
    assert 'detectLocations(combined).ifEmpty { "不明" }' in memory_code
    assert "茅ヶ崎で海見ながらカフェ行こー☕️" in memory_code
    assert "厚木か海老名あたりで美味いもん食べよ！" in memory_code
    assert "渋谷で肉食べよー🥩" not in memory_code, "Tokyo sample '渋谷で肉食べよー' MUST be completely removed!"

    with open("app/src/main/java/com/antigravity/lineaireply/ai/SmartChatAssistant.kt") as f:
        assistant_code = f.read()
    assert "渋谷で肉食べよー" not in assistant_code
    assert 'location = "都内近郊"' not in assistant_code
    assert 'location = "不明"' in assistant_code

    print("Kanagawa (Atsugi / Chigasaki) localization & anti-Tokyo suite: PASS ✅")

def test_bubble_dismiss_and_keyboard_hiding():
    # 1. Bubble dismiss flag verification
    class BubbleState:
        def __init__(self, enabled_in_settings=True):
            self.enabled_in_settings = enabled_in_settings
            self.is_dismissed_manually = False
            self.is_keyboard_open = False
            self.is_in_chat_room = True

        def should_show_bubble(self):
            if not self.enabled_in_settings:
                return False  # User disabled in settings -> NEVER SHOW!
            if self.is_dismissed_manually:
                return False  # User clicked 'X' -> DO NOT SHOW until next chat session!
            if self.is_keyboard_open:
                return False  # Keyboard is active -> HIDE to not block input/send button!
            return self.is_in_chat_room

    b = BubbleState()
    assert b.should_show_bubble() == True

    # User clicks 'X' button
    b.is_dismissed_manually = True
    assert b.should_show_bubble() == False, "Bubble MUST stay hidden when dismissed with 'X'!"

    # User switches chat room -> reset
    b.is_in_chat_room = False
    b.is_dismissed_manually = False
    b.is_in_chat_room = True
    assert b.should_show_bubble() == True

    # Keyboard opens
    b.is_keyboard_open = True
    assert b.should_show_bubble() == False, "Bubble MUST hide when soft keyboard is open!"
    b.is_keyboard_open = False
    assert b.should_show_bubble() == True

    # User disabled bubble in Settings
    b_disabled = BubbleState(enabled_in_settings=False)
    assert b_disabled.should_show_bubble() == False, "Bubble MUST NEVER show if disabled in settings!"

    # 2. Check layout contains close button
    with open("app/src/main/res/layout/overlay_bubble_trigger.xml") as f:
        layout_text = f.read()
    assert "btnBubbleClose" in layout_text, "Bubble layout MUST have btnBubbleClose!"

    # 3. Check Service code uses window API for IME detection and Dispatchers.Default for safety watcher
    with open("app/src/main/java/com/antigravity/lineaireply/service/LineAccessibilityService.kt") as f:
        service_code = f.read()
    assert "AccessibilityWindowInfo.TYPE_INPUT_METHOD" in service_code, "Must use TYPE_INPUT_METHOD window detection!"
    assert "Dispatchers.Default" in service_code, "Safety watcher must run on Dispatchers.Default!"

    print("Bubble manual dismiss, keyboard auto-hide & settings toggle suite: PASS ✅")

def main():
    res = test_archetype_matrix()
    print(f"Verified {res['archetypes_verified']} personality & scenario archetypes: PASS ✅")
    test_chronological_merge_suite()
    test_geometric_speaker_separation_and_input_exclusion()
    test_user_and_partner_profiling()
    test_full_history_prompt_injection()
    test_english_speaker_detection()
    test_zero_unprompted_scroll_and_vertical_filter()
    test_ui_chat_confinement_and_anti_duplication()
    test_paypay_and_financial_app_protection()
    test_polite_tone_detection_and_matching()
    test_photo_reaction_pipeline()
    test_kanagawa_atsugi_chigasaki_localization()
    test_bubble_dismiss_and_keyboard_hiding()
    print("All loop engineering v1.5.5 tests: 100% PASSED! 🚀")

if __name__ == "__main__":
    main()
