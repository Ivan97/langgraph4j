
/**
 * @file
 * @typedef {import('./types.js').ResultData} ResultData
 * @typedef {import('./types.js').EditEvent} EditEvent
 * @typedef {import('./types.js').UpdatedState} UpdatedState
 * @typedef {import('./types.js').InitData} InitData
 * @typedef {import('./types.js').ArgumentMetadata} ArgumentMetadata
 * 
 */

/**
 * Asynchronously waits for a specified number of milliseconds.
 * 
 * @param {number} ms - The number of milliseconds to wait.
 * @returns {Promise<void>} A promise that resolves after the specified delay.
 */
const delay = async (ms) => (new Promise(resolve => setTimeout(resolve, ms)));

export const adaptiveRAG = {
  /**
   * @param   {HTMLElement}  elem  
   * @returns {Promise<InitData>}
   */
  callInit: async (elem) => {

    /** @typedef {InitData} */
    const detail = {
      threads: [['default', []]],
      title: 'LangGraph4j : TEST',
      args: [
        { name: 'input', type: 'STRING', required: true }
      ],
      graph: `
---
title: TEST
---        
flowchart TD
  start((start))
  stop((stop))
  web_search("web_search")
  retrieve("retrieve")
  grade_documents("grade_documents")
  generate("generate")
  transform_query("transform_query")
  start:::start -->|web_search| web_search:::web_search
  start:::start -->|vectorstore| retrieve:::retrieve
  web_search:::web_search --> generate:::generate
  retrieve:::retrieve --> grade_documents:::grade_documents
  grade_documents:::grade_documents -->|transform_query| transform_query:::transform_query
  grade_documents:::grade_documents -->|generate| generate:::generate
  transform_query:::transform_query --> retrieve:::retrieve
  generate:::generate -->|not supported| generate:::generate
  generate:::generate -->|not useful| transform_query:::transform_query
  generate:::generate -->|useful| stop:::stop
      `
    }

    await delay(1000);

    /** @typedef {CustomEvent<InitData>} */
    const event = new CustomEvent('init', {
      detail,
      bubbles: true,
      composed: true,
      cancelable: true
    });


    elem.dispatchEvent(event);

    // @ts-ignore
    return detail;
  },


  /**
   * @param   {HTMLElement}  elem
   * @param   {string|undefined}  selectedThread 
   */
  callSubmitAction: async (elem, selectedThread) => {

    const thread = selectedThread

    const send = async ( /** @type {string} */ nodeId) => {

      /** @typedef {ResultData} */
      const detail = [thread, {
        checkpoint: (nodeId === 'start' || nodeId === 'stop') ? undefined : `checkpoint-${nodeId}`,
        node: nodeId,
        state: {
          input: "this is input",
          property1: { value: "value1", valid: true },
          property2: { value: "value2", children: { elements: [1, 2, 3] } }
        }
      }
      ]

      /** @typedef {CustomEvent<ResultData>} */
      const event = new CustomEvent('result', {
        detail,
        bubbles: true,
        composed: true,
        cancelable: true
      });

      await delay(1000);
      elem.dispatchEvent(event);
    }

    await send('start');
    await send('retrieve');
    await send('grade_documents');
    await send('transform_query');
    await send('retrieve');
    await send('grade_documents');
    await send('generate');
    await send('stop');

  }

};

export const imageToDiagram = {
  /**
   * @param   {HTMLElement}  elem  
   * @returns {Promise<InitData>}
   */
  callInit: async (elem) => {

    /** @typedef {InitData} */
    const detail = {
      threads: [['default', []]],
      title: 'LangGraph4j : TEST',
      args: [
        { name: 'input', type: 'IMAGE', required: true }
      ],
      graph: `
---
title: Image to diagram with correction
---
flowchart TD
	__START__((start))
	__END__((stop))
	agent_describer("agent_describer")
	agent_sequence_plantuml("agent_sequence_plantuml")
	agent_generic_plantuml("agent_generic_plantuml")
subgraph evaluate_result
	___START__((start)):::___START__
	___END__((stop)):::___END__
	_evaluate_result("evaluate_result")
	_agent_review("agent_review")
	%%	_condition1{"check state"}
	___START__:::___START__ --> _evaluate_result:::_evaluate_result
	_agent_review:::_agent_review --> _evaluate_result:::_evaluate_result
	%%	_evaluate_result:::_evaluate_result --> _condition1:::_condition1
	%%	_condition1:::_condition1 -->|ERROR| _agent_review:::_agent_review
	_evaluate_result:::_evaluate_result -->|ERROR| _agent_review:::_agent_review
	%%	_condition1:::_condition1 -->|UNKNOWN| ___END__:::___END__
	_evaluate_result:::_evaluate_result -->|UNKNOWN| ___END__:::___END__
	%%	_condition1:::_condition1 -->|OK| ___END__:::___END__
	_evaluate_result:::_evaluate_result -->|OK| ___END__:::___END__
end
	%%	condition1{"check state"}
	__START__:::__START__ --> agent_describer:::agent_describer
	%%	agent_describer:::agent_describer --> condition1:::condition1
	%%	condition1:::condition1 -->|sequence| agent_sequence_plantuml:::agent_sequence_plantuml
	agent_describer:::agent_describer -->|sequence| agent_sequence_plantuml:::agent_sequence_plantuml
	%%	condition1:::condition1 -->|generic| agent_generic_plantuml:::agent_generic_plantuml
	agent_describer:::agent_describer -->|generic| agent_generic_plantuml:::agent_generic_plantuml
	agent_sequence_plantuml:::agent_sequence_plantuml --> evaluate_result:::evaluate_result
	agent_generic_plantuml:::agent_generic_plantuml --> evaluate_result:::evaluate_result
	evaluate_result:::evaluate_result --> __END__:::__END__

	classDef ___START__ fill:black,stroke-width:1px,font-size:xx-small;
	classDef ___END__ fill:black,stroke-width:1px,font-size:xx-small;
  `
    }

    await delay(1000);

    /** @typedef {CustomEvent<InitData>} */
    const event = new CustomEvent('init', {
      detail,
      bubbles: true,
      composed: true,
      cancelable: true
    });

    elem.dispatchEvent(event);

    // @ts-ignore
    return detail
  },


  /**
  * @param   {HTMLElement}  elem
  * @param   {string|undefined}  selectedThread 
  */
  callSubmitAction: async (elem, selectedThread) => {

    const thread = selectedThread

    const send = async ( /** @type {string} */ nodeId) => {

      /** @typedef {ResultData} */
      const detail = [thread, {
        checkpoint: (nodeId === 'start' || nodeId === 'stop') ? undefined : `checkpoint-${nodeId}`,
        node: nodeId,
        state: {
          input: "this is input",
          property1: { value: "value1", valid: true },
          property2: { value: "value2", children: { elements: [1, 2, 3] } }
        }
      }]

      /** @typedef {CustomEvent<ResultData>} */
      const event = new CustomEvent('result', {
        detail,
        bubbles: true,
        composed: true,
        cancelable: true
      });

      await delay(1000);
      elem.dispatchEvent(event);
    }

    await send('__START__');
    await send('agent_describer');
    await send('agent_generic_plantuml');
    await send('___START__');
    await send('_evaluate_result');
    await send('___END__');
    await send('__END__');

  }

}