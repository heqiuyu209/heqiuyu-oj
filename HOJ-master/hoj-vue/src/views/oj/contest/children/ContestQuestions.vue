<template><div v-if="contest.id"><MixedContestEntry v-if="contest.mixedPaperId" /><ContestProblemList v-else /></div></template>
<script>
import MixedContestEntry from './MixedContestEntry';
import ContestProblemList from './ContestProblemList';
export default { components: { MixedContestEntry, ContestProblemList }, computed: { contest() { return this.$store.state.contest.contest; } } };
</script>
