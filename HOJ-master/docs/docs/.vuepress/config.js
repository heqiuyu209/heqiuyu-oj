const path = require('path');
const { config } = require("vuepress-theme-hope");

module.exports = context => config({
    title: 'CDUTETC · 成理工程在线测评平台',
    locales: { '/': { lang: 'zh-CN' } },
    head: [
        ['link', { rel: 'icon', href: `/cdutetc.webp` }],
        ['meta', { name: 'theme-color', content: '#217c9e' }],
    ],
    plugins: [
        '@vuepress/back-to-top',
        '@vuepress/active-header-links',
        'vuepress-plugin-nprogress',
        'vuepress-plugin-smooth-scroll',
        'vuepress-plugin-zooming'
    ],
    base:'/',
    themeConfig: {
        logo: '/cdutetc.webp',
        nav: [
            { text: '项目源码', link: 'https://github.com/heqiuyu209/heqiuyu-oj' },
            { text: 'API 源码', link: 'https://github.com/heqiuyu209/heqiuyu-oj/tree/master/HOJ-master/hoj-springboot' },
        ],
        pwa: {
            cacheHTML: false,
        },
        sidebarDepth: 2,
        // Enable these after configuring this project's real public hostname.
        feed: false,
        sitemap: false,
        repo: 'heqiuyu209/heqiuyu-oj',
        pageInfo: [],
        copyright: false,
        mdEnhance: {
            align: true,
            sup: true,
            sub: true,
            footnote: true,
            tex: true,
        },
        docsRepo: 'heqiuyu209/heqiuyu-oj',
        docsBranch: 'master',
        docsDir:'HOJ-master/docs/docs',
        editLinks: true,
        displayAllHeaders: true,
        smoothScroll: true,
        sidebar: [
            {
                title: '开始介绍',
                collapsable: true,
                children: [
                    'introducition/',
					'introducition/architecture'
                ]
            },
			{
                title: '快速部署',
                collapsable: true,
                children: [
                    'deploy/env',
					'deploy/docker',
					'deploy/setting',
					'deploy/open-https',
					'deploy/multi-judgeserver',
					'deploy/update',
					'deploy/how-to-backup',
					'deploy/faq'
                ]
            },
			{
                title: '单体部署',
                collapsable: true,
                children: [
                    'monomer/mysql',
					'monomer/mysql-checker',
					'monomer/redis',
					'monomer/nacos',
                    'monomer/backend',
                    'monomer/judgeserver',
					'monomer/frontend',
                    'monomer/rsync'
                ]
            },
			{
                title: '开发文档',
                collapsable: true,
                children: [
					'develop/db',
					'develop/judge_dispatcher',
					'develop/sandbox',
					'develop/backend'
                ]
            },
			{
                title: 'AI 智能学习系统',
                collapsable: true,
                children: [
                    'ai/',
                    'ai/TODO',
                    'ai/01-architecture',
                    'ai/02-rag-knowledge-base',
                    'ai/03-ai-qa',
                    'ai/04-learning-workbench',
                    'ai/05-model-providers',
                    'ai/06-security-and-governance',
                    'ai/07-codeforces-vj',
                    'ai/08-data-model-and-api',
                    'ai/09-learning-and-verification'
                ]
            },
			{
                title: '使用文档',
                collapsable: true,
                children: [
					'use/import-problem',
					'use/judge-mode',
					'use/judge-case-mode',
					'use/cancel-judge',
					'use/testcase',
					'use/training',
					'use/contest',
					'use/scroll-board',
					'use/group',
					'use/import-user',
					'use/admin-user',
					'use/notice-announcement',
					'use/discussion-admin',
					'use/update-fe',
					'use/custom-difficulty',
					'use/close-free-cdn'
                ]
            },
        ],
    },
    evergreen: !!context.isProd,
})
