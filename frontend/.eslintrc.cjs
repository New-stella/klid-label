/* eslint-env node */
module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:import/recommended',
    'plugin:import/typescript',
  ],
  parser: '@typescript-eslint/parser',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } },
  plugins: ['@typescript-eslint', 'react', 'react-hooks', 'jsx-a11y', 'import'],
  settings: {
    react: { version: 'detect' },
    'import/resolver': {
      typescript: { project: './tsconfig.json' },
      node: true,
    },
  },
  rules: {
    'react/react-in-jsx-scope': 'off',
    'react/prop-types': 'off',
    // jsx-a11y/aria-role 은 기본값(ignoreNonDOM: false)에서 **커스텀 컴포넌트의 `role` prop**
    // 까지 ARIA role 속성으로 오인해 잘못된 error 를 낸다(예: `<RoleBadge role="REVIEWER" />`).
    // ignoreNonDOM 은 그 오탐을 없애기 위해 이 규칙이 제공하는 공식 옵션이며, DOM 요소의
    // 실제 `role` 속성 검사는 그대로 유지된다(= 접근성 검사가 약해지지 않는다).
    // ⚠ `role` prop 을 다른 이름으로 바꿔 회피하지 말 것 — UI-110 이 prop 명을 `role` 로
    //   규정하고 있어 이름을 바꾸면 사양과 코드가 갈린다.
    'jsx-a11y/aria-role': ['error', { ignoreNonDOM: true }],
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    '@typescript-eslint/no-explicit-any': 'error',
    'import/order': [
      'warn',
      {
        groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
        'newlines-between': 'always',
      },
    ],
    'import/no-unresolved': 'off',
  },
  ignorePatterns: ['dist', 'node_modules', '*.cjs', 'src/lib/api/generated.ts'],
};
