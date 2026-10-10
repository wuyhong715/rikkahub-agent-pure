package me.rerere.rikkahub.data.ai.prompts

/**
 * What the file library asks a vision model when it indexes an image.
 *
 * Deliberately not [DEFAULT_OCR_PROMPT], even though the same model answers both. That one exists
 * to hand a chat model the *text* an image the user just sent contains, and it says so ("Extract
 * all visible text…"). This one is asked once per file, with no conversation around it, to produce
 * something that will later be *found* - so it asks for the words a person would search with, and
 * for the language they would search in.
 *
 * Chinese by default, unlike the prompts this app inherited from upstream: the product is
 * Chinese-first, the index is searched in the language its owner types in, and a description in the
 * wrong language is one the embedding model has to translate before it can match anything. The
 * identifiers, error codes and file names are asked for verbatim regardless, because those are
 * exactly the tokens a search matches exactly.
 */
val DEFAULT_LIBRARY_IMAGE_PROMPT =
    """
    用一段话描述这张图片，目的是让它以后能被搜索到。

    说清楚它是什么、以及里面有什么，用一个人找它时可能会打的词：
    - 它是什么（截图、照片、图表、流程图、扫描件、代码、报错界面……）；
    - 属于什么应用、网站、项目或文件；
    - 图上重要的文字：标题、报错信息、函数名或类名、变量名、文件名、界面上的按钮和标签；
    - 画面里的主体、场景或值得注意的物体。

    要具体、直白。报错码、函数名、类名、文件名等标识符请照抄原文，不要翻译、不要改写。
    只输出这段描述本身：不要打招呼，不要解释你在做什么，不要加评论或客套话。
    """.trimIndent()
